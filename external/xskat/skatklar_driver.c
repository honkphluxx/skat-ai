/*
 * SkatKlar external-bot driver for XSkat.
 *
 * Seats XSkat's own player at one seat of a game refereed from outside, and
 * speaks the line protocol in docs/external-bots.md over stdin/stdout.
 *
 * NOT PART OF XSKAT. XSkat's own sources are compiled unmodified and are not
 * redistributed with this file; see docs/xskat.md for the licence and the
 * arrangement. The only build-level change is -Dmain=xskat_main_unused on
 * skat.c, so that this file may provide main() instead.
 *
 * The whole point of the design is that XSkat plays a real game of its own:
 * every card, ours and the arena's alike, goes through the same calc_poss /
 * drop_card / do_next path its own program uses, so its void tracking and its
 * card memory are built the way they always are rather than reconstructed here.
 * What this file replaces is the X11 layer (the ~44 symbols below, all no-ops
 * except drop_card) and the auction, which the arena decides instead.
 */

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#ifdef _WIN32
#include <io.h>
#define DUP_FD _dup
#define DEV_NULL "NUL"
#else
#include <unistd.h>
#define DUP_FD dup
#define DEV_NULL "/dev/null"
#endif
#include "defs.h"
#include "skat.h"
#include "text.h"
#include "null.h"
#include "ramsch.h"

#define DRIVER_VERSION "skatklar-xskat-1"

/* ------------------------------------------------------------------ */
/* The X11 layer, stubbed. Every one of these is called by skat.c,     */
/* null.c or ramsch.c; all but drop_card only ever drew something.     */
/* ------------------------------------------------------------------ */

static int game_over;

VOID calc_desk(sn) int sn; { (void)sn; }
VOID clear_info() {}
VOID clr_desk(nsp) int nsp; { (void)nsp; }
VOID b_text(s,str) int s; VOID *str; { (void)s; (void)str; }
VOID do_msaho(sn,str) int sn; char *str; { (void)sn; (void)str; }
VOID draw_skat(sn) int sn; { (void)sn; }
VOID givecard(s,n) int s,n; { (void)s; (void)n; }
VOID home_skat() {}
VOID info_reiz() {}
VOID info_spiel() {}
VOID info_stich(a,b) int a,b; { (void)a; (void)b; }
VOID initscr(sn,sor) int sn,sor; { (void)sn; (void)sor; }
VOID inv_box(s,c,rev) int s,c,rev; { (void)s; (void)c; (void)rev; }
VOID nimm_stich() {}
VOID put_box(s) int s; { (void)s; }
VOID put_fbox(sn,t) int sn,t; { (void)sn; (void)t; }
VOID putmark(s) int s; { (void)s; }
VOID rem_box(s) int s; { (void)s; }
VOID remmark(f) int f; { (void)f; }
VOID revolutionscr() {}
VOID setcurs(f) int f; { (void)f; }
VOID show_hint(sn,c,d) int sn,c,d; { (void)sn; (void)c; (void)d; }
VOID stdwait() {}
VOID waitt(t,f) int t,f; { (void)t; (void)f; }
VOID xinit(argc,argv) int argc; char **argv; { (void)argc; (void)argv; }
VOID hndl_events() {}
VOID exitus(n) int n; { exit(n); }

VOID di_copyr(f) int f; { (void)f; }
VOID di_delliste() {}
VOID di_grandhand(sn) int sn; { (void)sn; }
VOID di_hand() {}
VOID di_info(sn,n) int sn,n; { (void)sn; (void)n; }
VOID di_options(n) int n; { (void)n; }
VOID di_proto(a,b,c) int a,b,c; { (void)a; (void)b; (void)c; }
VOID di_schieben() {}
VOID di_spiel() {}
int di_verdoppelt(a,b) int a,b; { (void)a; (void)b; return 0; }
VOID di_wiederweiter(f) int f; { (void)f; }
VOID di_weiter(ini) int ini; { (void)ini; game_over=1; }
VOID di_result(b) int b; { (void)b; game_over=1; }

/*
 * "The rest are mine" -- XSkat offers to stop playing and score the remaining
 * tricks itself. The arena wants all ten tricks actually played, and setting
 * ndichtw is exactly how XSkat's own code says "no, play it out".
 */
VOID di_dicht() { ndichtw=1; }

/*
 * The declarer's contract announcement. In XSkat this dialog is where a game
 * is chosen; here the arena has already chosen it, so this only carries the
 * decision on and hands control back to XSkat's own do_angesagt().
 */
static int forced_trumpf, forced_hand, forced_ouvert, forced_schneider, forced_schwarz;

VOID di_ansage()
{
    trumpf = forced_trumpf;
    handsp = forced_hand;
    ouveang = forced_ouvert;
    schnang = forced_schneider;
    schwang = forced_schwarz;
    spitzeang = 0;
    revolang = 0;
    do_angesagt();
}

/* drop_card, stripped of the animation: the four lines that are state. */
VOID drop_card(i,s) int i,s;
{
    (void)s;
    stcd[vmh] = cards[i];
    gespcd[cards[i]] = 2;
    if ((cards[i]&7) != BUBE) gespfb[cards[i]>>3]++;
    cards[i] = -1;
}

/* ------------------------------------------------------------------ */
/* Card notation. XSkat packs a card as suit<<3|rank, suits in the     */
/* order Diamonds, Hearts, Spades, Clubs and ranks A T K Q J 9 8 7.    */
/* ------------------------------------------------------------------ */

/*
 * The protocol does not share stdout with XSkat.
 *
 * calc_result() ends every game with a printf of the game value -- "Kreuz 12,
 * ohne 1, Spiel 2, Verloren." -- straight to stdout, which lands in the middle
 * of a reply and puts the pipe one line out of step for the rest of the match.
 * So fd 1 is duplicated for the protocol before anything else runs and stdout
 * is pointed at the null device: XSkat may print whatever it likes.
 */
static FILE *proto;

static const char SUITC[4] = {'D','H','S','C'};
static const char RANKC[8] = {'A','T','K','Q','J','9','8','7'};

static int parse_card(const char *s)
{
    int f, w;
    if (!s || !s[0] || !s[1]) return -1;
    for (f=0; f<4 && SUITC[f]!=s[0]; f++);
    for (w=0; w<8 && RANKC[w]!=s[1]; w++);
    if (f==4 || w==8) return -1;
    return (f<<3)|w;
}

static void print_card(FILE *f, int c)
{
    if (c < 0) { fputs("--", f); return; }
    fputc(SUITC[c>>3], f);
    fputc(RANKC[c&7], f);
}

/* ------------------------------------------------------------------ */
/* Driver state                                                        */
/* ------------------------------------------------------------------ */

static int myseat = 0;
static int blind;                 /* sampled world instead of the true deal */
static int probe_worlds;          /* 0 = off */
static long probe_decisions, probe_mismatches;
static long probe_mm_declarer, probe_mm_defender, probe_mm_trick[11];
static long legality_divergences;
static unsigned long probe_rng = 12345;

static int probe_next(int n)
{
    probe_rng = probe_rng*6364136223846793005UL + 1442695040888963407UL;
    return (int)((probe_rng >> 33) % (unsigned long)n);
}

static void set_bid(int value)
{
    int i;
    reizp = 0;
    for (i=0; reizw[i]<999; i++) {
        if (reizw[i] <= value) reizp = i;
    }
}

/*
 * One card of the game, whoever plays it. This is do_spielen()'s body with
 * the choice for foreign seats supplied from outside instead of computed --
 * everything else, including the calc_poss() call whose side effect is how
 * XSkat learns that a seat is void in a suit, happens for every seat exactly
 * as it does in XSkat's own loop.
 */
static void preamble(int s)
{
    int i;
    if (s==spieler && trumpf!=5) {
        adjfb(left(spieler),2);
        adjfb(right(spieler),2);
        for (i=0;i<5;i++) {
            if (!hatnfb[left(spieler)][i] || !hatnfb[right(spieler)][i]) {
                if (hatnfb[left(spieler)][i]==2) hatnfb[left(spieler)][i]=0;
                if (hatnfb[right(spieler)][i]==2) hatnfb[right(spieler)][i]=0;
            }
        }
    }
    if (ouveang) adjfb(spieler,1);
    calc_poss(s);
}

/*
 * Everything a decision may quietly write.
 *
 * make_best() is not a pure function: it marks cards in gespcd, updates the
 * high-card tables, and the Null and Ramsch players keep tallies of their own.
 * The control has to put all of it back, or a probed match plays differently
 * from an unprobed one -- which happened, and is why this list exists rather
 * than a shorter one that looked sufficient.
 */
struct decision_state {
    int cards[32], gespcd[32], gespfb[4];
    int hatnfb[3][5], high[5], shigh[5], inhand[4][8], spcards[12];
    int wirftabfb[4], hattefb[4], aussplfb[4], nochinfb[4], naussplfb[3];
    int rstsum[3], rstich[3], ggdurchm[3];
    int butternok, sptruempfe, karobubeanz, stsum, astsum, gstsum;
    int nullv, schwz, spitzeok, kannspitze, ndichtw;
    long seed1;
};

static void save_state(struct decision_state *d)
{
    memcpy(d->cards, cards, sizeof(cards));
    memcpy(d->gespcd, gespcd, sizeof(gespcd));
    memcpy(d->gespfb, gespfb, sizeof(gespfb));
    memcpy(d->hatnfb, hatnfb, sizeof(hatnfb));
    memcpy(d->high, high, sizeof(high));
    memcpy(d->shigh, shigh, sizeof(shigh));
    memcpy(d->inhand, inhand, sizeof(inhand));
    memcpy(d->spcards, spcards, sizeof(spcards));
    memcpy(d->wirftabfb, wirftabfb, sizeof(wirftabfb));
    memcpy(d->hattefb, hattefb, sizeof(hattefb));
    memcpy(d->aussplfb, aussplfb, sizeof(aussplfb));
    memcpy(d->nochinfb, nochinfb, sizeof(nochinfb));
    memcpy(d->naussplfb, naussplfb, sizeof(naussplfb));
    memcpy(d->rstsum, rstsum, sizeof(rstsum));
    memcpy(d->rstich, rstich, sizeof(rstich));
    memcpy(d->ggdurchm, ggdurchm, sizeof(ggdurchm));
    d->butternok = butternok; d->sptruempfe = sptruempfe;
    d->karobubeanz = karobubeanz; d->stsum = stsum;
    d->astsum = astsum; d->gstsum = gstsum;
    d->nullv = nullv; d->schwz = schwz; d->spitzeok = spitzeok;
    d->kannspitze = kannspitze; d->ndichtw = ndichtw;
    d->seed1 = seed[1];
}

static void restore_state(const struct decision_state *d)
{
    memcpy(cards, d->cards, sizeof(cards));
    memcpy(gespcd, d->gespcd, sizeof(gespcd));
    memcpy(gespfb, d->gespfb, sizeof(gespfb));
    memcpy(hatnfb, d->hatnfb, sizeof(hatnfb));
    memcpy(high, d->high, sizeof(high));
    memcpy(shigh, d->shigh, sizeof(shigh));
    memcpy(inhand, d->inhand, sizeof(inhand));
    memcpy(spcards, d->spcards, sizeof(spcards));
    memcpy(wirftabfb, d->wirftabfb, sizeof(wirftabfb));
    memcpy(hattefb, d->hattefb, sizeof(hattefb));
    memcpy(aussplfb, d->aussplfb, sizeof(aussplfb));
    memcpy(nochinfb, d->nochinfb, sizeof(nochinfb));
    memcpy(naussplfb, d->naussplfb, sizeof(naussplfb));
    memcpy(rstsum, d->rstsum, sizeof(rstsum));
    memcpy(rstich, d->rstich, sizeof(rstich));
    memcpy(ggdurchm, d->ggdurchm, sizeof(ggdurchm));
    butternok = d->butternok; sptruempfe = d->sptruempfe;
    karobubeanz = d->karobubeanz; stsum = d->stsum;
    astsum = d->astsum; gstsum = d->gstsum;
    nullv = d->nullv; schwz = d->schwz; spitzeok = d->spitzeok;
    kannspitze = d->kannspitze; ndichtw = d->ndichtw;
    seed[1] = d->seed1;
}

/*
 * The honesty control. Permutes every card this seat has not seen -- the two
 * foreign hands, and the skat as well when this seat is not entitled to it --
 * and asks again. A player that reads only its own cards answers the same card
 * every time; one that peeks does not. Everything touched is restored, so a
 * probe cannot change the game it is run inside.
 */
static int probe_choice(int s)
{
    struct decision_state saved;
    int pool[32], poolc = 0, slots[32], slotc = 0;
    int i, k, t, first = -1, mismatch = 0;
    int skat_hidden = (s != spieler) || handsp;

    save_state(&saved);

    for (t=0;t<3;t++) {
        if (t == s) continue;
        for (i=0;i<10;i++) {
            if (cards[10*t+i] >= 0) { pool[poolc++] = cards[10*t+i]; slots[slotc++] = 10*t+i; }
        }
    }
    if (skat_hidden) {
        for (i=30;i<32;i++) {
            if (cards[i] >= 0) { pool[poolc++] = cards[i]; slots[slotc++] = i; }
        }
    }
    if (poolc < 2) { probe_decisions++; return 0; }

    for (k=0;k<probe_worlds;k++) {
        restore_state(&saved);
        for (i=poolc-1;i>0;i--) {
            int j = probe_next(i+1), h = pool[i];
            pool[i] = pool[j]; pool[j] = h;
        }
        for (i=0;i<slotc;i++) cards[slots[i]] = pool[i];
        if (skat_hidden) {
            spcards[10] = cards[30];
            spcards[11] = cards[31];
        }
        preamble(s);
        make_best(s);
        if (first < 0) first = cards[possi[playcd]];
        else if (cards[possi[playcd]] != first) mismatch = 1;
    }

    restore_state(&saved);
    probe_decisions++;
    if (mismatch) {
        probe_mismatches++;
        if (s == spieler) probe_mm_declarer++; else probe_mm_defender++;
        if (stich >= 1 && stich <= 10) probe_mm_trick[stich]++;
    }
    return mismatch;
}

/*
 * Blind mode.
 *
 * The probe showed XSkat's play does depend on cards its seat cannot see: it
 * reads the skat's identity (gespcd marks it, and gewinnstich sums the points
 * still in hands, which is 120 minus what is taken minus the skat) and, as
 * declarer in a hand game, the skat again through adjfb. That is XSkat's own
 * behaviour in its own program, where one process plays every seat -- but it is
 * not something the arena may credit it with.
 *
 * So blind mode deals it a world drawn uniformly from the ones its seat cannot
 * tell apart: its own cards and the play so far are real, everything else is a
 * sample. The dependence stays; the advantage does not. When a seat then plays
 * a card the sample had put somewhere else, the sample is repaired by swapping
 * -- both cards are unseen, so the world stays consistent and stays uniform.
 */
static int unseen_slots(int s, int *slots)
{
    int t, i, n = 0;
    for (t=0;t<3;t++) {
        if (t == s) continue;
        for (i=0;i<10;i++) if (cards[10*t+i] >= 0) slots[n++] = 10*t+i;
    }
    if (s != spieler || handsp) {
        for (i=30;i<32;i++) if (cards[i] >= 0) slots[n++] = i;
    }
    return n;
}

static void refresh_skat(void)
{
    save_skat(0);
    save_skat(1);
    spcards[10] = cards[30];
    spcards[11] = cards[31];
    stsum = cardw[cards[30]&7] + cardw[cards[31]&7];
}

static void sample_world(int s)
{
    int slots[32], pool[32], n, i, j, h;
    n = unseen_slots(s, slots);
    for (i=0;i<n;i++) pool[i] = cards[slots[i]];
    for (i=n-1;i>0;i--) {
        j = probe_next(i+1);
        h = pool[i]; pool[i] = pool[j]; pool[j] = h;
    }
    for (i=0;i<n;i++) cards[slots[i]] = pool[i];
    if (s != spieler || handsp) refresh_skat();
}

/* The repair: make it true that seat t holds card c, without telling us more. */
static void ensure_holds(int t, int c)
{
    int i, from = -1, to = -1;
    for (i=0;i<10;i++) if (cards[10*t+i] == c) return;
    for (i=0;i<32;i++) if (cards[i] == c) { from = i; break; }
    if (from < 0) return;                       /* not in the sample at all */
    for (i=0;i<10;i++) if (cards[10*t+i] >= 0) { to = 10*t+i; break; }
    if (to < 0) return;
    cards[from] = cards[to];
    cards[to] = c;
    if (from >= 30) {
        gespcd[cards[from]] = gespcd[c];
        refresh_skat();
    }
}

/* Plays one card. forced < 0 means "let XSkat choose". Returns the card. */
static int step(int forced)
{
    int s = (ausspl+vmh)%3;
    int i, idx = -1;

    if (blind && forced >= 0 && s != myseat) ensure_holds(s, forced);
    preamble(s);
    if (forced < 0) {
        if (probe_worlds > 1) {
            probe_choice(s);
            preamble(s);          /* probe_choice left hatnfb restored; redo */
        }
        make_best(s);
        idx = possi[playcd];
    } else {
        for (i=0;i<possc;i++) {
            if (cards[possi[i]] == forced) { playcd = i; idx = possi[i]; break; }
        }
        if (idx < 0) {
            /* Our engine and XSkat disagree about what is legal here, or the
               card is not in that seat's hand at all. Both are worth counting
               rather than hiding; play it anyway so the game can continue. */
            for (i=0;i<10;i++) {
                if (cards[10*s+i] == forced) { idx = 10*s+i; break; }
            }
            legality_divergences++;
            if (idx < 0) return -1;
            playcd = 0;
        }
    }
    hintcard[0] = idx;
    drop_card(idx, s);
    do_next();
    return cards[idx] < 0 ? forced : forced;   /* card value captured by caller */
}

/* ------------------------------------------------------------------ */
/* Game setup                                                          */
/* ------------------------------------------------------------------ */

static int contract_to_trumpf(const char *c)
{
    switch (c[0]) {
    case 'D': return 0;
    case 'H': return 1;
    case 'S': return 2;
    case 'C': return 3;
    case 'G': return 4;
    case 'N': return -1;
    case 'R': return 5;
    default:  return -2;
    }
}

static char trumpf_to_contract(int t)
{
    switch (t) {
    case 0: return 'D';
    case 1: return 'H';
    case 2: return 'S';
    case 3: return 'C';
    case 4: return 'G';
    case 5: return 'R';
    default: return 'N';
    }
}

/* Fresh deal: let XSkat initialise everything exactly as it does per game. */
static void fresh_deal(void)
{
    dlhintseen = 1;
    firstgame = 0;
    wieder = 0;
    game_over = 0;
    do_geben();
}

int main(int argc, char *argv[])
{
    char line[1024], *tok, *seq;
    int i, s;

    (void)argc; (void)argv;
    proto = fdopen(DUP_FD(1), "w");
    if (!proto) return 1;
    setvbuf(proto, (char *)0, _IOLBF, 0);
    if (!freopen(DEV_NULL, "w", stdout)) return 1;

    /* Auto-mode conventions: no screens, no rule variants beyond the canon. */
    numsp = 0;
    numgames = 0;
    logging = 0;
    unformatted = 1;
    list_file = (char *)0;
    game_file = (char *)0;
    prot_file = (char *)0;
    opt_file = (char *)0;
    playramsch = ramschset = 0;
    playsramsch = sramschset = 0;
    playkontra = kontraset = 0;
    playbock = bockset = 0;
    spitzezaehlt = 0;
    revolution = 0;
    klopfen = 0;
    schenken = 0;
    oldrules = 0;
    fastdeal = 1;
    for (i=0;i<3;i++) { strateg[i] = 0; hints[i] = 0; lang[i] = 0; alist[i] = 0; }
    spwert_text = malloc(256);
    if (spwert_text) spwert_text[0] = 0;
    init_text();
    setrnd(&seed[0], savseed = 1);
    setrnd(&seed[1], seed[0]);

    while (fgets(line, sizeof(line), stdin)) {
        char *nl = strchr(line, '\n');
        if (nl) *nl = 0;
        tok = strtok(line, " \t");
        if (!tok) continue;
        /* Every request carries a sequence number and every reply repeats it.
           A helper that is one reply out of step is otherwise invisible until
           it reads as a weaker player, which is the worst way to find out. */
        seq = tok;
        tok = strtok((char *)0, " \t");
        if (!tok) continue;
        fprintf(proto, "%s ", seq);

        if (!strcmp(tok, "HELLO")) {
            fprintf(proto, "HELLO %s\n", DRIVER_VERSION);
        }
        else if (!strcmp(tok, "SEED")) {
            long v = atol(strtok((char *)0, " \t"));
            setrnd(&seed[0], savseed = v ? v : 1);
            setrnd(&seed[1], seed[0]);
            probe_rng = (unsigned long)(v ? v : 1) * 2654435761UL + 1;
            /* Counters are per deal: the arena reads them once a game and adds
               them up, so a pooled process cannot double-count. */
            probe_decisions = probe_mismatches = legality_divergences = 0;
            probe_mm_declarer = probe_mm_defender = 0;
            for (i=0;i<11;i++) probe_mm_trick[i] = 0;
            fprintf(proto, "OK\n");
        }
        else if (!strcmp(tok, "BLIND")) {
            blind = atoi(strtok((char *)0, " \t"));
            fprintf(proto, "OK\n");
        }
        else if (!strcmp(tok, "PROBE")) {
            probe_worlds = atoi(strtok((char *)0, " \t"));
            fprintf(proto, "OK\n");
        }
        else if (!strcmp(tok, "STATS")) {
            fprintf(proto, "STATS decisions %ld mismatches %ld divergences %ld"
                   " declarer %ld defender %ld tricks",
                   probe_decisions, probe_mismatches, legality_divergences,
                   probe_mm_declarer, probe_mm_defender);
            for (i=1;i<=10;i++) fprintf(proto, " %ld", probe_mm_trick[i]);
            fprintf(proto, "\n");
        }
        /* MAXBID <seat> <10 cards> -- the highest XSkat would go on this hand */
        else if (!strcmp(tok, "MAXBID")) {
            fresh_deal();
            s = atoi(strtok((char *)0, " \t"));
            for (i=0;i<10;i++) cards[10*s+i] = parse_card(strtok((char *)0, " \t"));
            calc_rw(s);
            fprintf(proto, "MAXBID %d\n", maxrw[s]);
        }
        /* HANDGAME <seat> <bid> <leader> <10 cards> -- play without the skat? */
        else if (!strcmp(tok, "HANDGAME")) {
            s = atoi(strtok((char *)0, " \t"));
            set_bid(atoi(strtok((char *)0, " \t")));
            ausspl = atoi(strtok((char *)0, " \t"));
            spieler = s;
            for (i=0;i<10;i++) spcards[i] = parse_card(strtok((char *)0, " \t"));
            for (i=0;i<10;i++) cards[10*s+i] = spcards[i];
            {
                int hand = testhand() ? 1 : 0;   /* testhand also settles trumpf */
                fprintf(proto, "HANDGAME %d GAME %c\n", hand, trumpf_to_contract(trumpf));
            }
        }
        /* DISCARD <seat> <bid> <leader> <10 hand> <2 skat> */
        else if (!strcmp(tok, "DISCARD")) {
            int c1, c2;
            s = atoi(strtok((char *)0, " \t"));
            set_bid(atoi(strtok((char *)0, " \t")));
            ausspl = atoi(strtok((char *)0, " \t"));
            spieler = s;
            handsp = 0;
            gedr = 0;
            for (i=0;i<10;i++) cards[10*s+i] = parse_card(strtok((char *)0, " \t"));
            cards[30] = parse_card(strtok((char *)0, " \t"));
            cards[31] = parse_card(strtok((char *)0, " \t"));
            for (i=0;i<10;i++) spcards[i] = cards[10*s+i];
            spcards[10] = cards[30];
            spcards[11] = cards[31];
            calc_drueck();
            c1 = cards[30]; c2 = cards[31];
            fprintf(proto, "DISCARD ");
            print_card(proto, c1);
            fprintf(proto, " ");
            print_card(proto, c2);
            fprintf(proto, " GAME %c\n", trumpf_to_contract(trumpf));
        }
        /* GAME <myseat> <declarer> <contract> <hand> <ouvert> <leader> <bid>
                <30 cards, seat 0 then 1 then 2> <2 skat> */
        else if (!strcmp(tok, "GAME")) {
            myseat = atoi(strtok((char *)0, " \t"));
            fresh_deal();
            spieler = atoi(strtok((char *)0, " \t"));
            forced_trumpf = contract_to_trumpf(strtok((char *)0, " \t"));
            forced_hand = atoi(strtok((char *)0, " \t"));
            forced_ouvert = atoi(strtok((char *)0, " \t"));
            ausspl = atoi(strtok((char *)0, " \t"));
            set_bid(atoi(strtok((char *)0, " \t")));
            forced_schneider = forced_schwarz = 0;
            for (i=0;i<30;i++) cards[i] = parse_card(strtok((char *)0, " \t"));
            cards[30] = parse_card(strtok((char *)0, " \t"));
            cards[31] = parse_card(strtok((char *)0, " \t"));

            /* What do_handspiel/do_handok do, minus the discard: the arena has
               already made it, and letting calc_drueck run again would discard
               a second time from a hand that has ten cards. */
            prot2.anspiel[0] = ausspl;
            prot2.gemacht[0] = -1;
            handsp = forced_hand;
            drkcd = 0;
            stsum = 0;
            vmh = 0;
            gedr = 2;
            for (i=0;i<10;i++) spcards[i] = cards[spieler*10+i];
            save_skat(0);
            spcards[10] = cards[30];
            spcards[11] = cards[31];
            stsum = cardw[cards[30]&7] + cardw[cards[31]&7];
            save_skat(1);
            kannspitze = 0;
            /* Sample before marking, or the marks would name the true skat. A
               defender's model of the declarer's running total starts from a
               sampled skat too, which is the whole point: it does not know it. */
            if (blind && (myseat != spieler || forced_hand)) sample_world(myseat);
            if (!forced_hand) {
                /* What calc_drueck does for a computer declarer. A hand game
                   never calls it, and XSkat leaves the skat unmarked there. */
                gespcd[cards[30]] = 1;
                gespcd[cards[31]] = 1;
            }
            di_ansage();              /* sets the contract and enters play */
            if (blind && myseat == spieler && !forced_hand) sample_world(myseat);
            ndichtw = 1;              /* never claim; the arena plays it out */
            if (trumpf == -1) init_null();
            fprintf(proto, "OK\n");
        }
        /* PLAY -- our seat's turn. Answers the card, and plays it. */
        else if (!strcmp(tok, "PLAY")) {
            int mover = (ausspl+vmh)%3, idx;
            if (mover != myseat) { fprintf(proto, "ERR turn %d\n", mover); continue; }
            preamble(mover);
            if (probe_worlds > 1) { probe_choice(mover); preamble(mover); }
            make_best(mover);
            idx = possi[playcd];
            fprintf(proto, "CARD ");
            print_card(proto, cards[idx]);
            fprintf(proto, "\n");
            hintcard[0] = idx;
            drop_card(idx, mover);
            do_next();
        }
        /* PLAYED <card> -- somebody else's card, from the arena. */
        else if (!strcmp(tok, "PLAYED")) {
            int c = parse_card(strtok((char *)0, " \t"));
            if (step(c) < 0 && c < 0) fprintf(proto, "ERR card\n");
            else fprintf(proto, "OK\n");
        }
        else if (!strcmp(tok, "QUIT")) {
            fprintf(proto, "BYE\n");
            break;
        }
        else {
            fprintf(proto, "ERR unknown %s\n", tok);
        }
        fflush(proto);
    }
    return 0;
}
