package dev.skatklar.demo.ai;

/** Factory boundary: the game core has no dependency on a concrete AI library. */
public interface SkatAiProvider {
    SkatAi.AiDescriptor descriptor();
    SkatAiSession createSession();
}
