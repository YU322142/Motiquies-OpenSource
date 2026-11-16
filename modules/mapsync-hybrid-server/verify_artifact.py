"""Read-only archive compatibility audit; no game launch."""
from pathlib import Path
import zipfile, hashlib, json, subprocess, argparse
root=Path(__file__).resolve().parent
base=None
cli=argparse.ArgumentParser();cli.add_argument('--base',type=Path,required=True);cli.add_argument('--artifact',type=Path,required=True);cli.add_argument('--java-home',type=Path,required=True);cli.add_argument('--output',type=Path,required=True);args=cli.parse_args();base=args.base
artifact=args.artifact or root/'build/mapsyncer-motiquies-download-neoforge-1.21.1.jar'
assert hashlib.sha256(base.read_bytes()).hexdigest()=='7e96f19dc703fe1a8c22b7deea8144e2b69c3632b3afc591d2bb84024f8c184a'
bytecode=subprocess.run([str(args.java_home/'bin'/('javap.exe' if __import__('os').name=='nt' else 'javap')),'-c','-classpath',str(artifact),
    'com.mapsyncer.network.impl.NeoForgeNetworkHandler'],capture_output=True,text=True,check=True).stdout
signature='public void sendToPlayer(net.minecraft.server.level.ServerPlayer, com.mapsyncer.network.payload.SyncProgressPayload);'
start=bytecode.index(signature)
progress=bytecode[start:bytecode.index('  public ',start+len(signature))]
assert 'hasChannel:' in progress and 'NeoForgeSyncProgressPayload.TYPE' in progress,'progress must guard negotiated channel before sending optional payload'
assert progress.index('hasChannel:') < progress.index('PacketDistributor.sendToPlayer'), 'channel check must precede send'
with zipfile.ZipFile(base) as original,zipfile.ZipFile(artifact) as overlay:
    assert len(overlay.namelist())==len(set(overlay.namelist())), 'duplicate entries'
    assert set(original.namelist()) <= set(overlay.namelist()), 'upstream entries removed'
    changed=[name for name in original.namelist() if original.read(name)!=overlay.read(name)]
    added=sorted(set(overlay.namelist())-set(original.namelist()))
    for name in changed+added:
        assert name.startswith('com/mapsyncer/server/') or name.startswith('org/motiquies/map/') or name.startswith('com/mapsyncer/network/impl/NeoForgeNetworkHandler') or name in ('LICENSE-GPL-3.0.txt','LICENSE-UPSTREAM-QOS.txt','NOTICE.md','com/mapsyncer/network/payload/MotiquiesDownloadControlPayload.class','com/mapsyncer/network/payload/MotiquiesMapUpdatedPayload.class','com/mapsyncer/network/payload/MotiquiesMapClockPayload.class','META-INF/neoforge.mods.toml','motiquies.mapsync.saved-regions.mixins.json'),name
    clients=[name for name in original.namelist() if name.startswith('com/mapsyncer/client/')]
    assert clients and all(original.read(n)==overlay.read(n) for n in clients)
    metadata=original.read('META-INF/neoforge.mods.toml')
    hybrid='motiquies.mapsync.saved-regions.mixins.json' in overlay.namelist()
    assert overlay.read('META-INF/neoforge.mods.toml')==metadata+(b'\n[[mixins]]\nconfig="motiquies.mapsync.saved-regions.mixins.json"\n' if hybrid else b'')
    for name in ('DownloadController','DownloadPolicy','DownloadSnapshot','DownloadGenerationGate','PublishedMapCache','ConversionOrchestrator'):
        assert 'com/mapsyncer/server/'+name+'.class' in overlay.namelist()
    result=dict(artifact=str(artifact),sha256=hashlib.sha256(artifact.read_bytes()).hexdigest(),size=artifact.stat().st_size,
                preserved_client_entries=len(clients),changed=changed,added=added,optional_mod_metadata_unchanged=not hybrid,only_saved_region_mixin_declaration_added=hybrid,
                optional_progress_has_channel_guard=True)
args.output.write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result,indent=2))
