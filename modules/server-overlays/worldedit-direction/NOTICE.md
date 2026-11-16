WorldEdit direction-property-fix.1, modified version reviewed 2026-11-16.

Modified file: source/NeoForgeTransmogrifier.java. Historical modification/audit date: 2026-08-14. The complete modified source carries its original upstream GPL header; this adjacent notice records the local changes without changing historical debug line numbers.

Copyright (C) sk89q <http://www.sk89q.com>
Copyright (C) WorldEdit team and contributors
Local direction-property compatibility changes: Motiquies contributors.
The Java class is derived from WorldEdit, licensed GPL-3.0-or-later as its intact header states. The local wrapper is offered under GPL-3.0-or-later; no enclosing MIT license overrides this directory. LICENSE.txt preserves the complete upstream combined notice.

Changes: recognize EnumProperty<net.minecraft.core.Direction> by value class in property creation and both value conversions, retaining ordinary enum serialization. Three DirectionProperty-specific checks are replaced; the two generated class entries are the only output entry contents changed. The source is kept byte-for-byte from the historical producer, including debug line numbers; this notice identifies it as modified.

No upstream binary/classes/assets are included here. Building produces a local derived JAR from operator-supplied dependencies. This source-only module does not establish a complete corresponding-source delivery for distributing the whole upstream JAR. Upstream full source and build files are available at the pinned revision in UPSTREAM.md. Review all GPL corresponding-source and bundled-component obligations before any binary redistribution.
