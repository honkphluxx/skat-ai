package main

// SkatKlar external-bot driver for go-skat.
//
// NOT PART OF go-skat. This file is additive: it changes nothing upstream and
// is enabled only by the -skatklar flag, which init() sees before main() runs.
// Everything else in this directory is Dranidis's go-skat under its own MIT
// licence; see docs/external-bots.md.
//
// Same design as the XSkat driver: go-skat plays a real game of its own, with
// the arena supplying the other two seats' cards, so the inference its tactics
// depend on is built exactly the way go-skat builds it -- analysePlay runs for
// every card, ours and theirs alike.

import (
	"bufio"
	"fmt"
	"math/rand"
	"os"
	"strconv"
	"strings"
)

const skatklarDriverVersion = "skatklar-goskat-1"

var (
	skSeats     [3]PlayerI
	skState     SuitState
	skMySeat    int
	skDeclarer  int
	skHandGame  bool
	skFinished  bool
	skBlind     bool
	skProbe     int
	skRng       *rand.Rand
	skDecisions int64
	skMismatch  int64
	skDiverge   int64
	// Where the mismatches fell. These used to be absent, and STATS reported a
	// literal "declarer 0 defender 0 tricks 0 0 ..." -- which the arena printed
	// beside XSkat's real breakdown, so two lines that looked identical meant
	// "none" for one helper and "not recorded" for the other. A zero that cannot
	// be anything else is worse than no column.
	skMmDeclarer int64
	skMmDefender int64
	skMmTrick    [11]int64
	skTricksDone int
	skOut        *bufio.Writer
)

func init() {
	for _, arg := range os.Args[1:] {
		if arg == "-skatklar" || arg == "--skatklar" {
			skatklarMain()
			os.Exit(0)
		}
	}
}

// ---- notation -------------------------------------------------------------

var skSuitOf = map[byte]string{'C': CLUBS, 'S': SPADE, 'H': HEART, 'D': CARO}
var skSuitLetter = map[string]byte{CLUBS: 'C', SPADE: 'S', HEART: 'H', CARO: 'D'}
// go-skat spells the queen "D", for Dame.
var skRankOf = map[byte]string{'A': "A", 'T': "10", 'K': "K", 'Q': "D", 'J': "J",
	'9': "9", '8': "8", '7': "7"}
var skRankLetter = map[string]byte{"A": 'A', "10": 'T', "K": 'K', "D": 'Q', "J": 'J',
	"9": '9', "8": '8', "7": '7'}

func skParseCard(text string) (Card, bool) {
	if len(text) != 2 {
		return Card{}, false
	}
	suit, okS := skSuitOf[text[0]]
	rank, okR := skRankOf[text[1]]
	if !okS || !okR {
		return Card{}, false
	}
	return Card{suit, rank}, true
}

func skCardText(c Card) string {
	return string([]byte{skSuitLetter[c.Suit], skRankLetter[c.Rank]})
}

func skTrumpOf(letter byte) string {
	switch letter {
	case 'C':
		return CLUBS
	case 'S':
		return SPADE
	case 'H':
		return HEART
	case 'D':
		return CARO
	case 'G':
		return GRAND
	default:
		return NULL
	}
}

func skTrumpLetter(trump string) string {
	switch trump {
	case CLUBS:
		return "C"
	case SPADE:
		return "S"
	case HEART:
		return "H"
	case CARO:
		return "D"
	case GRAND:
		return "G"
	case NULL:
		return "N"
	}
	return "N"
}

// ---- the loop -------------------------------------------------------------

func skatklarMain() {
	// go-skat prints its own commentary; none of it may reach the protocol.
	gameLogFlag = false
	fileLogFlag = false
	debugTacticsLogFlag = false
	verbose = false
	html = false
	issConnect = false
	delayMs = 0
	skRng = rand.New(rand.NewSource(1))
	r = rand.New(rand.NewSource(1))

	// go-skat prints its own commentary with fmt.Printf, which would land in
	// the middle of a reply and put the pipe one line out of step for the rest
	// of the match. fmt reads os.Stdout at call time, so keeping the real one
	// here and pointing the package variable at the null device separates them.
	skOut = bufio.NewWriter(os.Stdout)
	if devnull, err := os.OpenFile(os.DevNull, os.O_WRONLY, 0); err == nil {
		os.Stdout = devnull
	}
	in := bufio.NewScanner(os.Stdin)
	in.Buffer(make([]byte, 1024*1024), 1024*1024)

	for in.Scan() {
		fields := strings.Fields(in.Text())
		if len(fields) < 2 {
			continue
		}
		seq, args := fields[0], fields[2:]
		fmt.Fprintf(skOut, "%s ", seq)
		switch fields[1] {
		case "HELLO":
			fmt.Fprintf(skOut, "HELLO %s\n", skatklarDriverVersion)
		case "SEED":
			n, _ := strconv.ParseInt(args[0], 10, 64)
			if n == 0 {
				n = 1
			}
			skRng = rand.New(rand.NewSource(n))
			r = rand.New(rand.NewSource(n))
			skDecisions, skMismatch, skDiverge = 0, 0, 0
			fmt.Fprintf(skOut, "OK\n")
		case "PROBE":
			skProbe, _ = strconv.Atoi(args[0])
			fmt.Fprintf(skOut, "OK\n")
		case "BLIND":
			skBlind = args[0] == "1"
			fmt.Fprintf(skOut, "OK\n")
		case "STATS":
			fmt.Fprintf(skOut, "STATS decisions %d mismatches %d divergences %d"+
				" declarer %d defender %d tricks",
				skDecisions, skMismatch, skDiverge, skMmDeclarer, skMmDefender)
			for trick := 1; trick <= 10; trick++ {
				fmt.Fprintf(skOut, " %d", skMmTrick[trick])
			}
			fmt.Fprintf(skOut, "\n")
		case "MAXBID":
			seat, _ := strconv.Atoi(args[0])
			p := skStubTable(seat, skCards(args[1:]))
			fmt.Fprintf(skOut, "MAXBID %d\n", p.calculateHighestBid(false))
		case "HANDGAME":
			seat, _ := strconv.Atoi(args[0])
			p := skStubTable(seat, skCards(args[3:]))
			p.calculateHighestBid(false)
			hand2 := 0
			if p.handGame {
				hand2 = 1
			}
			fmt.Fprintf(skOut, "HANDGAME %d GAME %s\n", hand2, skTrumpLetter(p.declareTrump()))
		case "DISCARD":
			seat, _ := strconv.Atoi(args[0])
			skat := skCards(args[13:15])
			p := skStubTable(seat, skCards(args[3:13]))
			p.calculateHighestBid(false)
			p.handGame = false // the arena already decided this is a skat game
			p.pickUpSkat(skat)
			fmt.Fprintf(skOut, "DISCARD %s %s GAME %s\n",
				skCardText(skat[0]), skCardText(skat[1]), skTrumpLetter(p.declareTrump()))
		case "GAME":
			skSetupGame(args)
			fmt.Fprintf(skOut, "OK\n")
		case "PLAY":
			skPlayOurs()
		case "PLAYED":
			card, ok := skParseCard(args[0])
			if !ok {
				fmt.Fprintf(skOut, "ERR card\n")
			} else {
				skStep(&card)
				fmt.Fprintf(skOut, "OK\n")
			}
		case "QUIT":
			fmt.Fprintf(skOut, "BYE\n")
			skOut.Flush()
			return
		default:
			fmt.Fprintf(skOut, "ERR unknown %s\n", fields[1])
		}
		skOut.Flush()
	}
}

// go-skat's bidding runs a whole simulated game (calculateHighestBid ->
// autoGame -> the minimax player), and that reaches for the package-level
// `players` to find who the declarer is. So even a bidding question needs a
// table: our seat with its real hand, the other two empty.
func skStubTable(seat int, hand []Card) *Player {
	var mine *Player
	table := make([]PlayerI, 3)
	for i := 0; i < 3; i++ {
		cards := []Card{}
		if i == seat {
			cards = hand
		}
		p := makePlayer(cards)
		p.setName(fmt.Sprintf("P%d", i))
		table[i] = &p
		if i == seat {
			mine = &p
		}
	}
	players = table
	skSeats[0], skSeats[1], skSeats[2] = table[0], table[1], table[2]
	return mine
}

func skCards(texts []string) []Card {
	cards := make([]Card, 0, len(texts))
	for _, text := range texts {
		if card, ok := skParseCard(text); ok {
			cards = append(cards, card)
		}
	}
	return cards
}

// GAME <myseat> <declarer> <contract> <hand> <ouvert> <leader> <bid>
//      <30 cards> <2 skat>
func skSetupGame(args []string) {
	skMySeat, _ = strconv.Atoi(args[0])
	skDeclarer, _ = strconv.Atoi(args[1])
	trump := skTrumpOf(args[2][0])
	skHandGame = args[3] == "1"
	leader, _ := strconv.Atoi(args[5])
	skFinished = false
	skTricksDone = 0

	for seat := 0; seat < 3; seat++ {
		p := makePlayer(skCards(args[7+seat*10 : 17+seat*10]))
		p.setName(fmt.Sprintf("P%d", seat))
		skSeats[seat] = &p
	}
	skState = makeSuitState()
	skState.trump = trump
	skState.declarer = skSeats[skDeclarer]
	skState.opp1 = skSeats[(skDeclarer+1)%3]
	skState.opp2 = skSeats[(skDeclarer+2)%3]
	skat := skCards(args[37:39])
	skState.skat = []Card{skat[0], skat[1]}
	skState.trumpsInGame = filter(makeDeck(), func(c Card) bool {
		return getSuit(skState.trump, c) == skState.trump
	})
	// go-skat's inference reads the package-level `players` as the trick
	// order -- opponentIsLosingTrick indexes it against s.trick -- so this is
	// the same variable its own loop keeps, not a copy.
	players = []PlayerI{skSeats[leader], skSeats[(leader+1)%3], skSeats[(leader+2)%3]}
	skState.leader = players[0]
	skState.follow = ""
	for seat := 0; seat < 3; seat++ {
		skSeats[seat].setHand(sortSuit(trump, skSeats[seat].getHand()))
	}
	if skBlind {
		skSampleWorld()
	}
}

func skSeatOf(p PlayerI) int {
	for seat := 0; seat < 3; seat++ {
		if skSeats[seat] == p {
			return seat
		}
	}
	return -1
}

// Everything our seat cannot see: the two other hands, and the skat unless we
// are the declarer who picked it up.
func skHiddenPool() ([]Card, bool) {
	pool := []Card{}
	for seat := 0; seat < 3; seat++ {
		if seat == skMySeat {
			continue
		}
		pool = append(pool, skSeats[seat].getHand()...)
	}
	skatHidden := skMySeat != skDeclarer || skHandGame
	if skatHidden {
		pool = append(pool, skState.skat...)
	}
	return pool, skatHidden
}

func skDeal(pool []Card, skatHidden bool) {
	at := 0
	for seat := 0; seat < 3; seat++ {
		if seat == skMySeat {
			continue
		}
		n := len(skSeats[seat].getHand())
		hand := make([]Card, n)
		copy(hand, pool[at:at+n])
		at += n
		skSeats[seat].setHand(sortSuit(skState.trump, hand))
	}
	if skatHidden {
		skState.skat = []Card{pool[at], pool[at+1]}
	}
}

// A world drawn uniformly from the ones this seat cannot tell apart.
func skSampleWorld() {
	pool, skatHidden := skHiddenPool()
	skRng.Shuffle(len(pool), func(i, j int) { pool[i], pool[j] = pool[j], pool[i] })
	skDeal(pool, skatHidden)
}

// The repair: a seat played a card the sample had put somewhere else. Both are
// unseen, so swapping them keeps the world consistent and keeps it uniform.
func skEnsureHolds(seat int, card Card) {
	if in(skSeats[seat].getHand(), card) {
		return
	}
	for other := 0; other < 3; other++ {
		if other == seat || other == skMySeat {
			continue
		}
		hand := skSeats[other].getHand()
		for i, c := range hand {
			if c.equals(card) {
				mine := skSeats[seat].getHand()
				hand[i] = mine[0]
				mine[0] = card
				return
			}
		}
	}
	for i, c := range skState.skat {
		if c.equals(card) {
			mine := skSeats[seat].getHand()
			skState.skat[i] = mine[0]
			mine[0] = card
			return
		}
	}
}

// One card, whoever plays it: play()'s body with the choice supplied for the
// seats the arena owns.
func skStep(forced *Card) Card {
	if skFinished || len(players) == 0 {
		return Card{}
	}
	p := players[len(skState.trick)]
	if skBlind && forced != nil {
		skEnsureHolds(skSeatOf(p), *forced)
	}
	valid := validCards(skState, p.getHand())
	var card Card
	if forced != nil {
		card = *forced
		if !in(valid, card) {
			skDiverge++
			if !in(p.getHand(), card) {
				return card
			}
		}
	} else {
		card = p.playerTactic(&skState, valid)
	}
	analysePlay(&skState, p, card)
	p.setHand(remove(p.getHand(), card))
	skState.trick = append(skState.trick, card)
	if getSuit(skState.trump, card) == skState.trump {
		skState.trumpsInGame = remove(skState.trumpsInGame, card)
	}
	skState.cardsPlayed = append(skState.cardsPlayed, card)
	if len(skState.trick) == 1 {
		skState.follow = getSuit(skState.trump, skState.trick[0])
	}
	if len(skState.trick) == 3 {
		skTricksDone++
		players = setNextTrickOrder(&skState, players)
		skState.follow = ""
		if players == nil {
			skFinished = true // a Null the declarer has already lost
		}
	}
	return card
}

func skPlayOurs() {
	if skFinished || len(players) == 0 {
		fmt.Fprintf(skOut, "ERR finished\n")
		return
	}
	p := players[len(skState.trick)]
	if skSeatOf(p) != skMySeat {
		fmt.Fprintf(skOut, "ERR turn %d\n", skSeatOf(p))
		return
	}
	if skProbe > 1 {
		skProbeChoice(p)
	}
	card := skStep(nil)
	fmt.Fprintf(skOut, "CARD %s\n", skCardText(card))
}

// The honesty control: ask again under reshuffles of exactly what this seat has
// not seen. A player that reads only its own cards answers the same every time.
func skProbeChoice(p PlayerI) {
	savedHands := [3][]Card{}
	for seat := 0; seat < 3; seat++ {
		savedHands[seat] = append([]Card{}, skSeats[seat].getHand()...)
	}
	savedSkat := append([]Card{}, skState.skat...)
	pool, skatHidden := skHiddenPool()
	if len(pool) < 2 {
		skDecisions++
		return
	}
	first := ""
	mismatch := false
	// go-skat's tactics draw on the package generator. Swapping the pointer
	// aside and handing every world the same fresh stream keeps the control
	// measuring information rather than its own noise; `saved` is untouched
	// meanwhile, so the real game's stream continues where it left off.
	saved := r
	probeSeed := skRng.Int63()
	for world := 0; world < skProbe; world++ {
		r = rand.New(rand.NewSource(probeSeed))
		skRng.Shuffle(len(pool), func(i, j int) { pool[i], pool[j] = pool[j], pool[i] })
		skDeal(pool, skatHidden)
		probe := p.clone()
		probe.setHand(append([]Card{}, savedHands[skMySeat]...))
		probe.setName(p.getName())
		valid := validCards(skState, probe.getHand())
		choice := skCardText(probe.playerTactic(&skState, valid))
		if first == "" {
			first = choice
		} else if choice != first {
			mismatch = true
		}
	}
	r = saved
	for seat := 0; seat < 3; seat++ {
		skSeats[seat].setHand(savedHands[seat])
	}
	skState.skat = savedSkat
	skDecisions++
	if mismatch {
		skMismatch++
		if skMySeat == skDeclarer {
			skMmDeclarer++
		} else {
			skMmDefender++
		}
		// The trick this decision belongs to, numbered from 1 as XSkat numbers
		// it, so the two helpers' histograms line up column for column.
		if trick := skTricksDone + 1; trick >= 1 && trick <= 10 {
			skMmTrick[trick]++
		}
	}
}
