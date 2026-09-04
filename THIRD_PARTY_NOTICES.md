# Third-Party Notices

The engine itself has no third-party dependency of any kind. Everything below is
used by the arena, by the optional JSkat adapter, or by the tests.

| Component | Version | Licence | Use |
| --- | --- | --- | --- |
| [JSkat](https://github.com/b0n541/jskat-multimodule) | submodule, pinned revision of the fork below | Apache License 2.0 | The outside opponent the arena measures against, via `jskat-ai/`. Not part of the engine and not redistributed here. |
| [honkphluxx/jskat](https://github.com/honkphluxx/jskat) (fork of the above) | branch `skatklar` | Apache License 2.0 | What `third_party/jskat` actually points at: JSkat with five changes, two of them fixes for upstream defects. Stated in that repository's `CHANGES.md`, as section 4(b) of the licence asks. |
| [skat-ml-models](https://github.com/avaskys/skat-ml-models) | release artefacts | MIT License | The trained models JSkat's `jskat-ml` and `jskat-ml-pro` players load. Downloaded by a JSkat Gradle task into the submodule; not redistributed here. |
| [ONNX Runtime](https://onnxruntime.ai/) | 1.19.2 (override with `-PonnxVersion=`) | MIT License | Reads a trained belief model in the arena. The shipped path does not use it: `BeliefNet` is plain Java. |
| [SLF4J](https://www.slf4j.org/) | 2.0.17 | MIT License | Logging API required by JSkat. |
| [JUnit 4](https://junit.org/junit4/) | 4.13.2 | Eclipse Public License 1.0 | Tests. |

The Python side of the belief trainer (`arena/python/`) uses PyTorch and NumPy,
which it expects to find in the environment. It is not part of any build and no
part of it is redistributed here.
