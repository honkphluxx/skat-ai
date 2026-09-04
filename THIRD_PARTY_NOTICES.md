# Third-Party Notices

The engine itself has no third-party dependency of any kind. Everything below is
used by the arena, by the optional JSkat adapter, or by the tests.

| Component | Version | Licence | Use |
| --- | --- | --- | --- |
| [JSkat](https://github.com/jskat/jskat) | submodule, pinned revision | Apache License 2.0 | The outside opponent the arena measures against, via `jskat-ai/`. Not part of the engine and not redistributed here — a clone fetches it from its own repository. |
| [ONNX Runtime](https://onnxruntime.ai/) | 1.19.2 (override with `-PonnxVersion=`) | MIT License | Reads a trained belief model in the arena. The shipped path does not use it: `BeliefNet` is plain Java. |
| [SLF4J](https://www.slf4j.org/) | 2.0.17 | MIT License | Logging API required by JSkat. |
| [JUnit 4](https://junit.org/junit4/) | 4.13.2 | Eclipse Public License 1.0 | Tests. |

The Python side of the belief trainer (`arena/python/`) uses PyTorch and NumPy,
which it expects to find in the environment. It is not part of any build and no
part of it is redistributed here.
