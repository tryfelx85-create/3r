package com.arena.spawn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single-elimination tournament. An operator builds each round in the editor GUI (the "draft":
 * round 1 is built from whoever is eligible, later rounds only from the previous round's winners),
 * then starts it: every fight of that round is put in the fight queue and results are recorded as
 * matches end. The NEXT round is never created automatically - once a round is fully decided the
 * operator builds it manually with /bracket edit (see BracketGui, e.g. its "Random fill"). An odd
 * player out in the draft can be given a bye, or paired against the fallback (a Master-tagged
 * player). A drawn fight goes back into the queue and is replayed.
 */
public final class Bracket {

    public static final class Match {
        public final String a;
        public final String b; // null = bye
        public String winner;
        public boolean live;

        Match(String a, String b) {
            this.a = a;
            this.b = b;
        }

        public boolean isBye() {
            return b == null;
        }

        boolean has(String name) {
            return name != null && (a.equalsIgnoreCase(name) || (b != null && b.equalsIgnoreCase(name)));
        }

        String canonical(String name) {
            return a.equalsIgnoreCase(name) ? a : b;
        }

        String other(String name) {
            return a.equalsIgnoreCase(name) ? b : a;
        }
    }

    private static final List<List<Match>> rounds = new ArrayList<>();
    private static final List<String[]> draft = new ArrayList<>();
    private static boolean active;
    private static String champion;

    // ------------------------------------------------------------ state for the GUI

    public static boolean isActive() {
        return active;
    }

    public static String getChampion() {
        return champion;
    }

    public static List<List<Match>> rounds() {
        return Collections.unmodifiableList(rounds);
    }

    public static List<String[]> draft() {
        return draft;
    }

    public static void addDraft(String a, String b) {
        draft.add(new String[]{a, b});
    }

    /** Adds a bye (no opponent) to the draft; the entry wins its "match" the moment the round starts. */
    public static void addDraftBye(String name) {
        draft.add(new String[]{name, null});
    }

    public static void removeDraft(int index) {
        if (index >= 0 && index < draft.size()) draft.remove(index);
    }

    public static void clearDraft() {
        draft.clear();
    }

    public static boolean draftContains(String name) {
        for (String[] pair : draft) {
            if (pair[0].equalsIgnoreCase(name) || (pair[1] != null && pair[1].equalsIgnoreCase(name))) return true;
        }
        return false;
    }

    /** Whether the editor can be used right now: no round yet, or the last one is fully decided. */
    public static boolean canEdit() {
        return rounds.isEmpty() || roundReady();
    }

    /** True once every match of the current (last) round has a winner and there is no champion yet. */
    public static boolean roundReady() {
        return active && champion == null && !rounds.isEmpty() && isCurrentRoundComplete();
    }

    /** Message explaining why the editor is locked; only meaningful when canEdit() is false. */
    public static String editBlockedReason() {
        if (champion != null) {
            return "§cThe tournament is finished (champion: §f" + champion + "§c). Use §f/bracket reset §cto start a new one.";
        }
        return "§cThe current round is still in progress - wait for it to finish, then use §f/bracket edit§c.";
    }

    /** 1-based number of the round the draft would become if committed now. */
    public static int nextRoundNumber() {
        return rounds.size() + 1;
    }

    /** The winners of the last round, or empty if it is not fully decided yet. */
    public static List<String> winnersOfLastRound() {
        List<String> out = new ArrayList<>();
        if (!isCurrentRoundComplete()) return out;
        for (Match m : current()) out.add(m.winner);
        return out;
    }

    // ------------------------------------------------------------ lifecycle

    /** Locks the draft in as the next round (round 1, or the round after the last one) and queues its fights. */
    public static boolean commit() {
        if (!canEdit() || draft.isEmpty()) return false;

        boolean firstRound = rounds.isEmpty();
        List<Match> round = new ArrayList<>();
        for (String[] pair : draft) {
            Match m = new Match(pair[0], pair[1]);
            if (m.isBye()) m.winner = m.a; // a bye is won instantly
            round.add(m);
        }

        if (firstRound) {
            champion = null;
            active = true;
            TournamentManager.clear();
            Bukkit.broadcast(Component.text(
                    "§6§l[Tournament] §eThe tournament has started! §7Type §f/bracket §7to see the bracket."));
        }

        rounds.add(round);
        for (Match m : round) {
            if (!m.isBye()) TournamentManager.add(m.a, m.b);
        }
        announceRound(rounds.size(), round);
        TournamentManager.notifyNext();
        draft.clear();

        checkRoundComplete(); // handles the edge case of a round made entirely of byes
        BracketGui.refreshAll();
        return true;
    }

    public static void reset() {
        rounds.clear();
        draft.clear();
        active = false;
        champion = null;
        TournamentManager.clear();
        BracketGui.refreshAll();
    }

    // ------------------------------------------------------------ hooks from the match flow

    /** A queued fight between these two has just begun. */
    public static void onStart(String a, String b) {
        Match m = findPending(a, b);
        if (m != null) {
            m.live = true;
            BracketGui.refreshAll();
        }
    }

    /** A fight ended with a winner and a loser (either name may be null if that player is gone). */
    public static void onResult(String winner, String loser) {
        if (!active || rounds.isEmpty()) return;
        Match m = findLive(winner, loser);
        if (m == null) m = findPending(winner, loser);
        if (m == null) return;

        String won = null;
        if (winner != null && m.has(winner)) {
            won = m.canonical(winner);
        } else if (loser != null && m.has(loser)) {
            won = m.other(loser);
        }
        if (won == null) return;

        m.winner = won;
        m.live = false;
        checkRoundComplete();
        BracketGui.refreshAll();
    }

    /** A fight ended without a winner: it goes back to the end of the queue and is played again. */
    public static void onDraw(String a, String b) {
        if (!active) return;
        Match m = findLive(a, b);
        if (m == null) return;
        m.live = false;
        TournamentManager.add(m.a, m.b);
        Bukkit.broadcast(Component.text("§6[Tournament] §eDraw between §f" + m.a + " §eand §f" + m.b
                + "§e - the fight will be replayed."));
        BracketGui.refreshAll();
    }

    // ------------------------------------------------------------ internals

    private static Match findLive(String x, String y) {
        List<Match> current = current();
        if (current == null) return null;
        for (Match m : current) {
            if (m.winner == null && m.live && (m.has(x) || m.has(y))) return m;
        }
        return null;
    }

    private static Match findPending(String x, String y) {
        List<Match> current = current();
        if (current == null) return null;
        for (Match m : current) {
            if (m.winner == null && !m.isBye() && (m.has(x) || m.has(y))) return m;
        }
        return null;
    }

    private static List<Match> current() {
        return rounds.isEmpty() ? null : rounds.get(rounds.size() - 1);
    }

    private static boolean isCurrentRoundComplete() {
        List<Match> cur = current();
        if (cur == null || cur.isEmpty()) return false;
        for (Match m : cur) {
            if (m.winner == null) return false;
        }
        return true;
    }

    /** Crowns a champion if the current round came down to one winner; otherwise just announces it's decided. */
    private static void checkRoundComplete() {
        if (!isCurrentRoundComplete()) return;
        List<String> winners = winnersOfLastRound();

        if (winners.size() == 1) {
            champion = winners.get(0);
            active = false;
            Bukkit.broadcast(Component.text("§6§l[Tournament] §f" + champion + " §ewon the tournament!"));
            Title title = Title.title(
                    Component.text("§6§l" + champion),
                    Component.text("§eis the tournament champion!"),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(5), Duration.ofSeconds(1)));
            for (Player p : Bukkit.getOnlinePlayers()) p.showTitle(title);
        } else {
            Bukkit.broadcast(Component.text("§6§l[Tournament] §eRound " + rounds.size()
                    + " is complete! §7An operator can now build round " + (rounds.size() + 1) + " with §f/bracket edit§7."));
        }
    }

    private static void announceRound(int number, List<Match> matches) {
        Bukkit.broadcast(Component.text("§6§l[Tournament] §eRound " + number + ":"));
        for (Match m : matches) {
            Bukkit.broadcast(Component.text(m.isBye()
                    ? "  §f" + m.a + " §7has a bye and goes through"
                    : "  §f" + m.a + " §7vs §f" + m.b));
        }
    }
}
