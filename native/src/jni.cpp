// The JNI surface of the solver: dev.skatklar.demo.solve.NativeSolver.
//
// Deliberately thin, and deliberately primitive. Nothing here reads a Java
// object, allocates one, or looks a field or a method up by name: hands arrive
// as three ints, positions as ints, and the two calls that have more than one
// number to give back write into an array the caller already owns. That is not
// only for speed -- a search leaf that had to build a Card would be a different
// program -- but because every one of those operations can throw, and a JNI
// function that throws through a search is a crash rather than an exception.
//
// The Java side never sees a native error as something it has to remember: a
// negative return code means "this engine cannot answer that", and the caller
// falls back to the Java search for that one question.

#include <jni.h>

#include "skatsolve.h"

namespace {

inline void handsOf(jint h0, jint h1, jint h2, uint32_t out[3]) {
    out[0] = static_cast<uint32_t>(h0);
    out[1] = static_cast<uint32_t>(h1);
    out[2] = static_cast<uint32_t>(h2);
}

}  // namespace

// The one thing a cross-compiled build could get silently wrong. A Windows JDK
// spells jint "long" and a Linux one "int"; both are 32 bits, which is why the
// host's headers can be used to build for the other -- but "which is why" is an
// argument, and an argument is not a check.
static_assert(sizeof(jint) == 4, "jint must be 32 bits");
static_assert(sizeof(jlong) == 8, "jlong must be 64 bits");
static_assert(sizeof(jbyte) == 1, "jbyte must be one byte");

extern "C" {

JNIEXPORT jstring JNICALL
Java_dev_skatklar_demo_solve_NativeSolver_version(JNIEnv* env, jclass) {
    return env->NewStringUTF(skat_version());
}

JNIEXPORT jint JNICALL
Java_dev_skatklar_demo_solve_NativeSolver_solve(JNIEnv* env, jclass, jint contract,
                                                jint declarer, jint h0, jint h1, jint h2,
                                                jint leader, jlongArray outArray) {
    uint32_t hands[3];
    handsOf(h0, h1, h2, hands);
    SkatResult result{};
    int32_t status = skat_solve(contract, declarer, hands, leader, &result);
    if (status != SKAT_OK) return status;
    jlong out[3] = {result.declarerPoints, result.visitedNodes, result.transpositions};
    env->SetLongArrayRegion(outArray, 0, 3, out);
    return SKAT_OK;
}

JNIEXPORT jint JNICALL
Java_dev_skatklar_demo_solve_NativeSolver_reaches(JNIEnv*, jclass, jint contract,
                                                  jint declarer, jint h0, jint h1, jint h2,
                                                  jint toPlay, jint leader, jint trickCards,
                                                  jint trickSize, jint target) {
    uint32_t hands[3];
    handsOf(h0, h1, h2, hands);
    return skat_reaches(contract, declarer, hands, toPlay, leader,
                        static_cast<uint32_t>(trickCards), trickSize, target);
}

JNIEXPORT jint JNICALL
Java_dev_skatklar_demo_solve_NativeSolver_reachesWithin(JNIEnv*, jclass, jint contract,
                                                        jint declarer, jint h0, jint h1,
                                                        jint h2, jint toPlay, jint leader,
                                                        jint trickCards, jint trickSize,
                                                        jint target, jlong budgetNanos) {
    uint32_t hands[3];
    handsOf(h0, h1, h2, hands);
    return skat_reaches_within(contract, declarer, hands, toPlay, leader,
                               static_cast<uint32_t>(trickCards), trickSize, target,
                               budgetNanos);
}

JNIEXPORT jint JNICALL
Java_dev_skatklar_demo_solve_NativeSolver_bestCard(JNIEnv* env, jclass, jint contract,
                                                   jint declarer, jint toPlay, jint h0,
                                                   jint h1, jint h2, jint leader,
                                                   jint trickCards, jint trickSize,
                                                   jlongArray outArray) {
    uint32_t hands[3];
    handsOf(h0, h1, h2, hands);
    SkatResult result{};
    int32_t status = skat_best_card(contract, declarer, toPlay, hands, leader,
                                    static_cast<uint32_t>(trickCards), trickSize, &result);
    if (status != SKAT_OK) return status;
    jlong out[3] = {result.card, result.declarerPoints, result.visitedNodes};
    env->SetLongArrayRegion(outArray, 0, 3, out);
    return SKAT_OK;
}

JNIEXPORT jint JNICALL
Java_dev_skatklar_demo_solve_NativeSolver_bestCardForResult(
        JNIEnv* env, jclass, jint contract, jint declarer, jint toPlay, jint h0, jint h1,
        jint h2, jint leader, jint trickCards, jint trickSize, jint banked,
        jlongArray outArray) {
    uint32_t hands[3];
    handsOf(h0, h1, h2, hands);
    SkatResult result{};
    int32_t status = skat_best_card_for_result(contract, declarer, toPlay, hands, leader,
                                               static_cast<uint32_t>(trickCards), trickSize,
                                               banked, &result);
    if (status != SKAT_OK) return status;
    jlong out[3] = {result.card, result.declarerPoints, result.visitedNodes};
    env->SetLongArrayRegion(outArray, 0, 3, out);
    return SKAT_OK;
}

JNIEXPORT jint JNICALL
Java_dev_skatklar_demo_solve_NativeSolver_movesReaching(
        JNIEnv* env, jclass, jint contract, jint declarer, jint toPlay, jint h0, jint h1,
        jint h2, jint leader, jint trickCards, jint trickSize, jint target,
        jintArray outCards, jintArray outBounds) {
    uint32_t hands[3];
    handsOf(h0, h1, h2, hands);
    int32_t cards[10];
    int32_t bounds[10];
    int32_t count = skat_moves_reaching(contract, declarer, toPlay, hands, leader,
                                        static_cast<uint32_t>(trickCards), trickSize,
                                        target, cards, bounds);
    if (count < 0) return count;
    env->SetIntArrayRegion(outCards, 0, count, cards);
    env->SetIntArrayRegion(outBounds, 0, count, bounds);
    return count;
}

JNIEXPORT jint JNICALL
Java_dev_skatklar_demo_solve_NativeSolver_brute(JNIEnv*, jclass, jint contract, jint declarer,
                                                jint h0, jint h1, jint h2, jint leader) {
    uint32_t hands[3];
    handsOf(h0, h1, h2, hands);
    int32_t value = 0;
    int32_t status = skat_brute(contract, declarer, hands, leader, &value);
    return status == SKAT_OK ? value : status;
}

JNIEXPORT jlong JNICALL
Java_dev_skatklar_demo_solve_NativeSolver_createSolver(JNIEnv*, jclass, jint contract,
                                                       jint declarer) {
    return reinterpret_cast<jlong>(skat_solver_create(contract, declarer));
}

JNIEXPORT void JNICALL
Java_dev_skatklar_demo_solve_NativeSolver_destroySolver(JNIEnv*, jclass, jlong handle) {
    skat_solver_destroy(reinterpret_cast<SkatSolver*>(handle));
}

JNIEXPORT jint JNICALL
Java_dev_skatklar_demo_solve_NativeSolver_solverReaches(JNIEnv*, jclass, jlong handle,
                                                        jint h0, jint h1, jint h2,
                                                        jint toPlay, jint leader,
                                                        jint trickCards, jint trickSize,
                                                        jint target) {
    uint32_t hands[3];
    handsOf(h0, h1, h2, hands);
    return skat_solver_reaches(reinterpret_cast<SkatSolver*>(handle), hands, toPlay, leader,
                               static_cast<uint32_t>(trickCards), trickSize, target);
}

JNIEXPORT void JNICALL
Java_dev_skatklar_demo_solve_NativeSolver_setTranspositions(JNIEnv*, jclass, jlong handle,
                                                            jboolean enabled) {
    skat_solver_set_transpositions(reinterpret_cast<SkatSolver*>(handle),
                                   enabled == JNI_TRUE ? 1 : 0);
}

}  // extern "C"
