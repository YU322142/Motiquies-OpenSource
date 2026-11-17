# SDK cold reproduction evidence — 2026-11-17

Two fresh GitHub-hosted Windows jobs completed the pinned NeoForge 21.1.241 recipe at commit `75ac46fe0a2796735aad641f9850a0b4161c11bd`.

[Actual run](https://github.com/YU322142/Motiquies-OpenSource/actions/runs/43985056034) · [Reviewed source PR](https://github.com/YU322142/Motiquies-OpenSource/pull/8)

Each job authenticated 136 initial objects through 139 transport legs (including three JDK asset redirects), executed the original 14 processor steps, and matched all seven frozen raw targets: six derived SDK parents and the official universal JAR. The 43,425 ZIP entry records, including 27,382 class entries, also match between jobs.

Recipe SHA256: `bf8a8b9cd9089271d5cf9379c2fdcd05ad956a6c04613054faa45e02fdf88b6c`.

| Target | Bytes | SHA256 |
| --- | ---: | --- |
| `net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-extra.jar` | 12372293 | `fd02f80f4eec9dfaa1714cb62595d013cbac03d52d19dd0623d1922b999c40b7` |
| `net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-slim.jar` | 14464635 | `93224371dda52351038d0957e392729e692dce031f55ae29ea4b47d909433a5c` |
| `net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar` | 18725656 | `a649bd3ce77f122d0f83ae1b37b4b303e662470f928b9bee5fbc76580805ffd4` |
| `net/neoforged/neoforge/21.1.241/neoforge-21.1.241-client.jar` | 5673830 | `8e3563a078289f0f07ee6f87f1c8651294387639c8355983ee207cb753f08b7f` |
| `net/neoforged/neoforge/21.1.241/neoforge-21.1.241-server.jar` | 5673830 | `cdea1e7366c1608b6e729d69013a811dd7d554bb2f86b95473fef5fb9ac4fe09` |
| `net/neoforged/neoforge/21.1.241/neoforge-21.1.241-universal.jar` | 3544283 | `4ceaf2c6a1bd9520bf77f227bde8e9eeb441764ecadd33ef1a4328dcbaff4d3f` |
| `net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar` | 14246609 | `26ca9c40d7e1681190b428583c38816852218e78df3f8bdb60a59a78503aec71` |

Only digest and ZIP metadata artifacts were uploaded. No SDK JARs, mappings, processor log bodies, private source, world or player data were uploaded. Original job metadata keeps `reproductionAccepted=false`; the maintainer accepted the independently reviewed evidence separately, without rewriting those receipts.

This result establishes the SDK parent derivation for the stated commit and inputs. It does not certify a complete modpack, game behavior, production readiness, third-party binary redistribution, or permission to publish Mechanomania or modified Nostalgia source. The recipe documents its ordinary network access and limited URL observations; no network isolation is claimed.
