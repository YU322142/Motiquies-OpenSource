# MapSyncer attribution and modifications

MapSyncer by Ruo_Chen / RuoChennn: https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap
Original source version: 1.0.4beta2, commit 04e5d9c542d5fd0e56fb63b3be87f0dba3091a47.
Upstream declares GPL-3.0 in README and mod metadata. This integrated module,
its modified upstream-generated classes and Motiquies contributions are supplied
under GNU GPL version 3; see LICENSE-GPL-3.0.txt. Copyright 2026 Motiquies maintainers
applies only to their own additions. Original upstream ownership remains intact.

2026-11-16: public external-SDK builder/source locks/documentation added.
Motiquies changes add negotiated download control, paced immutable snapshots,
successful native-save dirty-region routing and bounded replay, source freshness,
empty-layer publication and matching map-clock metadata. The producer modifies
only the pinned upstream classes and preserves original client classes.

META-INF/LICENSE.txt in the original JAR is the QOS.ch/Sarl SLF4J MIT notice,
retained as LICENSE-UPSTREAM-QOS.txt and left unchanged in rebuilt archives.
It is not the MapSyncer GPL license. Other original archive notices stay intact.
Minecraft, NeoForge and Xaero dependencies remain externally supplied and are
not relicensed by this source. No player/map/world/publisher-state data is included.
