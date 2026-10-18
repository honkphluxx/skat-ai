package dev.skatklar.training.data;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.belief.BeliefEncoding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Pairs of encodings for the trainer's suit permutation to be checked against
 * (arena/python/suit_permutation.py, plan 2.10 I5).
 *
 * <p>Each pair is a random position -- hands, a history of tricks, a trick on
 * the table, remembered plays and voids, a discard, a Schieben in a Ramsch,
 * bids, Kontra -- and the same position with every card moved by a
 * permutation of the suits, both encoded by {@link BeliefEncoding} itself. The
 * trumps and jacks still unseen are recomputed from the moved cards by the
 * encoder's own helpers, not carried over, so a permutation that is not a
 * symmetry of the contract (moving the trump suit, or the jacks outside a
 * Null) shows as a pair the Python table cannot reproduce. The positions need
 * not be reachable -- the encoder reads none of the rules -- only complete.
 *
 * <p>The file is committed (arena/python/fixtures/suit-permutation.bin);
 * {@code SuitPermutationFixturesTest} regenerates it in memory and fails when
 * the encoder no longer writes the same bytes, and the trainer refuses to
 * permute until the Python table reproduces every pair.
 *
 * <pre>./gradlew :arena:suitPermutationFixtures</pre>
 */
public final class SuitPermutationFixtures {

    static final byte[] MAGIC = "SKSP".getBytes(StandardCharsets.US_ASCII);
    static final int VERSION = 1;
    /** Positions drawn for each contract and each of its permutations. */
    static final int PER_PERMUTATION = 2;
    static final long SEED = 20261018L;
    static final Path DEFAULT_OUT = Path.of("python", "fixtures", "suit-permutation.bin");

    private static final int[] BIDS = {0, 18, 20, 22, 23, 24, 27, 30, 33, 35, 36, 40, 44, 45, 46,
        48, 50, 54, 55, 59, 60, 63, 66, 72, 96};

    private SuitPermutationFixtures() {}

    public static void main(String[] args) throws IOException {
        Path out = args.length > 0 ? Path.of(args[0]) : DEFAULT_OUT;
        Files.createDirectories(out.toAbsolutePath().getParent());
        byte[] bytes = generate();
        Files.write(out, bytes);
        Path spec = out.resolveSibling("encoding-v" + BeliefEncoding.VERSION + ".json");
        Files.writeString(spec, BeliefEncoding.specificationJson());
        System.out.println(out + ": " + (bytes.length - 16) / caseBytes() + " pairs, " + bytes.length
                + " bytes; " + spec);
    }

    static int caseBytes() {
        return 1 + 4 + 2 * BeliefEncoding.SIZE;
    }

    /** Every permutation of the four suit ordinals, in lexicographic order (the identity first). */
    static List<int[]> permutations() {
        List<int[]> out = new ArrayList<>();
        permute(new int[] {0, 1, 2, 3}, 0, out);
        out.sort((a, b) -> {
            for (int i = 0; i < 4; i++) if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
            return 0;
        });
        return out;
    }

    private static void permute(int[] a, int at, List<int[]> out) {
        if (at == a.length) { out.add(a.clone()); return; }
        for (int i = at; i < a.length; i++) {
            int t = a[at]; a[at] = a[i]; a[i] = t;
            permute(a, at + 1, out);
            t = a[at]; a[at] = a[i]; a[i] = t;
        }
    }

    /** The permutations a contract admits: the trump suit fixed in a suit game, all in the rest. */
    static boolean admits(Contract contract, int[] perm) {
        Card.Suit trump = contract.trumpSuit;
        return trump == null || perm[trump.ordinal()] == trump.ordinal();
    }

    /** The whole file. */
    public static byte[] generate() {
        Random random = new Random(SEED);
        List<byte[]> cases = new ArrayList<>();
        for (Contract contract : Contract.values()) {
            for (int[] perm : permutations()) {
                if (!admits(contract, perm)) continue;
                for (int i = 0; i < PER_PERMUTATION; i++) {
                    Position position = Position.random(contract, random);
                    byte[] before = BeliefEncoding.quantise(BeliefEncoding.encode(position.evidence()));
                    byte[] after = BeliefEncoding.quantise(BeliefEncoding.encode(position.moved(perm).evidence()));
                    ByteBuffer one = ByteBuffer.allocate(caseBytes());
                    one.put((byte) contract.ordinal());
                    for (int s : perm) one.put((byte) s);
                    one.put(before).put(after);
                    cases.add(one.array());
                }
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        header.put(MAGIC).putInt(VERSION).putInt(cases.size()).putInt(BeliefEncoding.SIZE);
        out.writeBytes(header.array());
        for (byte[] c : cases) out.writeBytes(c);
        return out.toByteArray();
    }

    /** Where a card goes: its suit moved, its rank kept; a jack stays unless the contract is a Null. */
    static Card move(Card card, int[] perm, Contract contract) {
        if (card.rank == Card.Rank.JACK && !contract.isNull()) return card;
        return new Card(Card.Suit.values()[perm[card.suit.ordinal()]], card.rank);
    }

    /** One complete position, everything the encoder reads, and the same position moved. */
    record Position(SkatAi.GameDefinition game, SkatAi.Seat me, List<Card> hand,
                    List<SkatAi.CompletedTrick> tricks, SkatAi.CurrentTrick current,
                    Set<Card> remembered, Map<SkatAi.Seat, Set<SkatAi.FollowClass>> voids,
                    List<Card> discard, Map<SkatAi.Seat, Integer> bids, boolean biddingRemembered,
                    boolean countsKept, BeliefEncoding.Schieben schieben,
                    SkatAi.ContraLevel contra, SkatAi.Seat contraSeat) {

        static Position random(Contract contract, Random random) {
            List<Card> deck = new ArrayList<>(SkatDeck.ordered());
            Collections.shuffle(deck, random);
            SkatAi.Seat[] seats = SkatAi.Seat.values();
            Map<SkatAi.Seat, List<Card>> hands = new EnumMap<>(SkatAi.Seat.class);
            for (int s = 0; s < 3; s++) hands.put(seats[s], new ArrayList<>(deck.subList(10 * s, 10 * s + 10)));
            List<Card> skat = new ArrayList<>(deck.subList(30, 32));
            SkatAi.Seat me = seats[random.nextInt(3)];
            SkatAi.Seat declarer = contract.isRamsch() ? null : seats[random.nextInt(3)];
            SkatAi.Seat leader = seats[random.nextInt(3)];
            int bidValue = contract.isRamsch() ? 0 : BIDS[1 + random.nextInt(BIDS.length - 1)];
            SkatAi.GameDefinition game = new SkatAi.GameDefinition(declarer, leader, contract,
                    new SkatAi.RoundPosition(0, leader.next().next()), bidValue);

            int completed = random.nextInt(9);
            List<SkatAi.CompletedTrick> tricks = new ArrayList<>();
            Set<Card> remembered = new LinkedHashSet<>();
            SkatAi.Seat lead = leader;
            for (int t = 0; t < completed; t++) {
                List<SkatAi.PlayedCard> plays = new ArrayList<>();
                SkatAi.Seat seat = lead;
                for (int k = 0; k < 3; k++) {
                    Card card = hands.get(seat).remove(random.nextInt(hands.get(seat).size()));
                    plays.add(new SkatAi.PlayedCard(seat, card));
                    if (random.nextInt(5) != 0) remembered.add(card);
                    seat = seat.next();
                }
                SkatAi.Seat winner = seats[random.nextInt(3)];
                tricks.add(new SkatAi.CompletedTrick(t + 1, lead, plays, winner, 0));
                lead = winner;
            }
            List<SkatAi.PlayedCard> onTable = new ArrayList<>();
            int showing = random.nextInt(3);
            SkatAi.Seat seat = lead;
            for (int k = 0; k < showing && seat != me; k++) {
                onTable.add(new SkatAi.PlayedCard(seat, hands.get(seat).remove(random.nextInt(hands.get(seat).size()))));
                seat = seat.next();
            }
            SkatAi.CurrentTrick current = new SkatAi.CurrentTrick(completed + 1, lead, onTable);

            Map<SkatAi.Seat, Set<SkatAi.FollowClass>> voids = new EnumMap<>(SkatAi.Seat.class);
            List<SkatAi.FollowClass> classes = new ArrayList<>();
            if (!contract.isNull()) classes.add(SkatAi.FollowClass.trump());
            for (Card.Suit suit : Card.Suit.values()) {
                if (suit != contract.trumpSuit) classes.add(SkatAi.FollowClass.suit(suit));
            }
            for (SkatAi.Seat other : seats) {
                Set<SkatAi.FollowClass> shown = new LinkedHashSet<>();
                for (SkatAi.FollowClass c : classes) if (random.nextInt(3) == 0) shown.add(c);
                voids.put(other, shown);
            }
            List<Card> discard = declarer == me && random.nextBoolean() ? skat : List.of();
            Map<SkatAi.Seat, Integer> bids = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat other : seats) bids.put(other, BIDS[random.nextInt(BIDS.length)]);
            BeliefEncoding.Schieben schieben = null;
            if (contract.isRamsch() && random.nextBoolean()) {
                List<Card> pool = new ArrayList<>(deck);
                Collections.shuffle(pool, random);
                schieben = new BeliefEncoding.Schieben(List.of(pool.get(0), pool.get(1)), random.nextBoolean(),
                        List.of(pool.get(2), pool.get(3)), random.nextBoolean());
            }
            SkatAi.ContraLevel contra = contract.isRamsch() ? SkatAi.ContraLevel.NONE
                    : SkatAi.ContraLevel.values()[random.nextInt(3)];
            SkatAi.Seat contraSeat = contra == SkatAi.ContraLevel.NONE ? null : seats[random.nextInt(3)];
            return new Position(game, me, hands.get(me), tricks, current, remembered, voids, discard, bids,
                    random.nextInt(4) != 0, random.nextInt(4) != 0, schieben, contra, contraSeat);
        }

        Position moved(int[] perm) {
            Contract c = game.contract;
            List<SkatAi.CompletedTrick> movedTricks = new ArrayList<>();
            for (SkatAi.CompletedTrick t : tricks) {
                movedTricks.add(new SkatAi.CompletedTrick(t.trickNumber, t.leader, plays(t.plays, perm, c),
                        t.winner, t.cardPoints));
            }
            SkatAi.CurrentTrick movedCurrent = new SkatAi.CurrentTrick(current.trickNumber, current.leader,
                    plays(current.plays, perm, c));
            Map<SkatAi.Seat, Set<SkatAi.FollowClass>> movedVoids = new EnumMap<>(SkatAi.Seat.class);
            for (Map.Entry<SkatAi.Seat, Set<SkatAi.FollowClass>> e : voids.entrySet()) {
                Set<SkatAi.FollowClass> shown = new LinkedHashSet<>();
                for (SkatAi.FollowClass f : e.getValue()) {
                    shown.add(f.trump ? f : SkatAi.FollowClass.suit(Card.Suit.values()[perm[f.suit.ordinal()]]));
                }
                movedVoids.put(e.getKey(), shown);
            }
            BeliefEncoding.Schieben movedSchieben = schieben == null ? null : new BeliefEncoding.Schieben(
                    cards(schieben.pushedOn(), perm, c), schieben.toSkat(),
                    cards(schieben.received(), perm, c), schieben.fromSkat());
            return new Position(game, me, cards(hand, perm, c), movedTricks, movedCurrent,
                    new LinkedHashSet<>(cards(new ArrayList<>(remembered), perm, c)), movedVoids,
                    cards(discard, perm, c), bids, biddingRemembered, countsKept, movedSchieben,
                    contra, contraSeat);
        }

        BeliefEncoding.Evidence evidence() {
            Set<Card> held = new LinkedHashSet<>(hand);
            SkatAi.DecisionContext context = new SkatAi.DecisionContext(game, me, held, held, current,
                    new SkatAi.GameHistory(tricks), new SkatAi.DerivedGameKnowledge(Set.of(), Map.of(),
                            Map.of(), Map.of()));
            return new BeliefEncoding.Evidence(context, remembered, voids, discard, bids, biddingRemembered,
                    countsKept ? BeliefEncoding.trumpsUnaccounted(game.contract, held, remembered) : null,
                    BeliefEncoding.jacksUnseen(held, remembered), schieben, contra, contraSeat);
        }

        private static List<SkatAi.PlayedCard> plays(List<SkatAi.PlayedCard> plays, int[] perm, Contract c) {
            List<SkatAi.PlayedCard> out = new ArrayList<>();
            for (SkatAi.PlayedCard p : plays) out.add(new SkatAi.PlayedCard(p.seat, move(p.card, perm, c)));
            return out;
        }

        private static List<Card> cards(List<Card> cards, int[] perm, Contract c) {
            List<Card> out = new ArrayList<>();
            for (Card card : cards) out.add(move(card, perm, c));
            return out;
        }
    }
}
