# Corresponding upstream source route and remaining checks

This public module contains the complete own patch producer and its build instructions. It does not contain the upstream Distant Horizons implementation or claim that an original binary input replaces corresponding source obligations.

The original external input is Distant Horizons3.2.0-b for Minecraft1.21.1, Fabric/NeoForge. Obtain it from the [official Modrinth version](https://modrinth.com/mod/distanthorizons/version/ZpKb4kZp) or [official CurseForge file8389148](https://www.curseforge.com/minecraft/mc-mods/distant-horizons/files/8389148); verify producer.json SHA256 before use. The public version page is verified; the release was not downloaded again during this audit, and local exact-input hash guards remain mandatory.

The official source repository is [Distant Horizons on GitLab](https://gitlab.com/distant-horizons-team/distant-horizons/). The pinned input build_info.json stores info_git_commit="main" and info_git_branch="6b60bd7a805cc057e786e83a2b02bee37967db34". Those labels appear swapped; do not report them as an unambiguous build certificate. The [official wrapper commit6b60bd7a](https://gitlab.com/distant-horizons-team/distant-horizons/-/commit/6b60bd7a805cc057e786e83a2b02bee37967db34) exists and points coreSubProjects to [core revision9d92715b](https://gitlab.com/jeseibel/distant-horizons-core/-/tree/9d92715b1edf041e780a5679dd6f470cc26cd51a). Its immutable .gitmodules uses https://gitlab.com/jeseibel/distant-horizons-core.git.

Suggested source acquisition route, subject to verifying availability and upstream build instructions:

```text
git clone https://gitlab.com/distant-horizons-team/distant-horizons.git
git -C distant-horizons checkout 6b60bd7a805cc057e786e83a2b02bee37967db34
git -C distant-horizons submodule update --init --recursive
git -C distant-horizons/coreSubProjects rev-parse HEAD
```

Expected core pointer:9d92715b1edf041e780a5679dd6f470cc26cd51a. Preserve all upstream copyright, license, bundled-dependency notices and build scripts. The published own producer plus the corresponding original source and full instructions are required to explain/recreate modifications; this document is a route, not a substitute for confirming complete source.

Still pending: independently fetch/verify every recursive submodule, establish full original release source/build equivalence, and assemble any complete corresponding-source distribution required before sharing a modified upstream binary. The core source tree was not successfully read by the web tool in this audit. No full-source reproducibility or binary redistribution clearance is claimed. LGPL3/GPL3 text copies are exact original input bytes. Logo notice is retained; no logo is distributed in this source module, and preserved logos in any generated original-derived archive retain Pankakes CC BY-SA4.0 attribution.
