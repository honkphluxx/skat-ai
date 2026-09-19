package dev.skatklar.demo.search;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The threads a search borrows, shared by card play and by hand evaluation.
 *
 * <p>One pool rather than one each, because the two never run at once: a seat is
 * either weighing a hand it has just been dealt or choosing a card, and the
 * auction is over before the first card is played. Two pools would mean twice
 * the idle threads for no overlap at all.
 *
 * <p>A holder class, so nothing is created in a process that never asks — which
 * is every arena run and every server, both of which leave the thread count at
 * one and use their cores on whole boards instead. The threads are daemons and
 * the pool is never shut down: the only thing that could own its lifetime is the
 * app, and an app that exits while a seat is mid-solve should exit.
 */
final class SearchWorkers {

    static final ExecutorService POOL = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors()), work -> {
                Thread thread = new Thread(work, "skat-search");
                thread.setDaemon(true);
                // Below the thread that draws the table. A search that is late
                // costs a beat; a frame that is late is visible.
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });

    private SearchWorkers() {}
}
