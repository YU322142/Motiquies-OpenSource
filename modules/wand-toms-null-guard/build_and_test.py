"""Pinned one-class patch; exercise the actual original/patched useItems bytecode with isolated world adapters."""
from pathlib import Path
import hashlib,json,os,subprocess,zipfile,sys
sys.dont_write_bytecode=True
from build_support import ROOT as P,Context,parser,deterministic_archive,overlay_receipt
cli=parser(__doc__);cli.add_argument('--base',required=True,type=Path)
args=cli.parse_args();context=Context(args);B=context.output
ORIGINAL=args.base.resolve(strict=True)
PIN=context.lock['originalJarSha256']
ENTRY='dev/polaris_light/constructionwand/containers/handlers/HandlerAdvWirelessTerminal.class'
assert hashlib.sha256(ORIGINAL.read_bytes()).hexdigest()==PIN
deps=context.deps
cp=os.pathsep.join(map(str,deps));tools=B/'patch-tools';tools.mkdir()
context.compile([P/'WandTomsPatch.java'],tools,'compile-tools')
stubs={
'net/minecraft/world/entity/player/Player.java':'package net.minecraft.world.entity.player;public class Player {}',
'net/minecraft/server/level/ServerPlayer.java':'package net.minecraft.server.level;public class ServerPlayer extends net.minecraft.world.entity.player.Player {}',
'net/minecraft/world/item/ItemStack.java':'package net.minecraft.world.item;public class ItemStack {}',
'dev/polaris_light/constructionwand/containers/ContainerTrace.java':'package dev.polaris_light.constructionwand.containers;public class ContainerTrace {}',
'com/tom/storagemod/inventory/StoredItemStack.java':'''package com.tom.storagemod.inventory;public class StoredItemStack {private final long quantity;public StoredItemStack(net.minecraft.world.item.ItemStack stack,long quantity){this.quantity=quantity;}public long getQuantity(){return quantity;}}''',
'com/tom/storagemod/block/entity/StorageTerminalBlockEntity.java':'''package com.tom.storagemod.block.entity;import com.tom.storagemod.inventory.StoredItemStack;public class StorageTerminalBlockEntity {public long stock;public int pulls;public boolean unavailable;public StorageTerminalBlockEntity(long stock){this.stock=stock;}public StoredItemStack pullStack(StoredItemStack request,long count){pulls++;if(unavailable||stock==0||count==0)return null;long taken=Math.min(count,stock);stock-=taken;return new StoredItemStack(new net.minecraft.world.item.ItemStack(),taken);}}''',
'org/motiquies/qa/WandHarnessState.java':'''package org.motiquies.qa;public class WandHarnessState {public static com.tom.storagemod.block.entity.StorageTerminalBlockEntity terminal;}''',
'org/motiquies/qa/WandHarnessTest.java':'''package org.motiquies.qa;
import dev.polaris_light.constructionwand.containers.handlers.HandlerAdvWirelessTerminal;
import com.tom.storagemod.block.entity.StorageTerminalBlockEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
public class WandHarnessTest {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args){
  var h=new HandlerAdvWirelessTerminal();var p=new ServerPlayer();var stack=new ItemStack();
  WandHarnessState.terminal=null;check(h.useItems(p,null,stack,stack,10)==10,"missing terminal retains request");
  WandHarnessState.terminal=new StorageTerminalBlockEntity(100);check(h.useItems(new Player(),null,stack,stack,10)==10,"client only leaves request");check(WandHarnessState.terminal.pulls==0,"client does not extract");
  WandHarnessState.terminal=new StorageTerminalBlockEntity(0);check(h.useItems(p,null,stack,stack,10)==10,"empty return is zero extracted, not free blocks");
  WandHarnessState.terminal=new StorageTerminalBlockEntity(50);WandHarnessState.terminal.unavailable=true;check(h.useItems(p,null,stack,stack,10)==10&&WandHarnessState.terminal.stock==50,"unavailable storage unchanged");
  WandHarnessState.terminal=new StorageTerminalBlockEntity(3);check(h.useItems(p,null,stack,stack,10)==7&&WandHarnessState.terminal.stock==0,"partial extraction");check(WandHarnessState.terminal.pulls==1,"never retry side effect");
  WandHarnessState.terminal=new StorageTerminalBlockEntity(100);check(h.useItems(p,null,stack,stack,10)==0&&WandHarnessState.terminal.stock==90,"full extraction");
  WandHarnessState.terminal=new StorageTerminalBlockEntity(50);long used=0;for(int i=0;i<10;i++)used+=6-h.useItems(p,null,stack,stack,6);check(used==50&&WandHarnessState.terminal.stock==0,"sequential depletion conserves items");
  check(h.useItems(p,null,stack,stack,0)==0,"zero request");System.out.println("PASS original method body: missing/client/null/unavailable/partial/full/repeated/zero; no free items, loss or duplicate extraction");
 }
}'''}
sources=[]
for name,text in stubs.items():
 file=B/'harness-src'/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(text,encoding='utf-8');sources.append(file)
with zipfile.ZipFile(ORIGINAL) as z:
 assert not any(n.endswith(('.SF','.RSA','.DSA')) for n in z.namelist())
 raw=z.read(ENTRY);(B/'original-handler.class').write_bytes(raw)
toolcp=os.pathsep.join([str(tools),cp]);red=B/'red-harness';green=B/'green-harness'
subprocess.run([str(context.java),'-cp',toolcp,'WandTomsPatch','red',str(B/'original-handler.class'),str(red)],check=True)
def compile_run(classes):
 subprocess.run([str(context.javac),'--release','21','-encoding','UTF-8','-cp',str(classes),'-d',str(classes),*map(str,sources)],check=True)
 return subprocess.run([str(context.java),'-Xverify:all','-cp',str(classes),'org.motiquies.qa.WandHarnessTest'],capture_output=True,text=True,encoding='utf-8',errors='replace')
r=compile_run(red);(B/'red-result.txt').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode!=0 and 'NullPointerException' in r.stderr,r.stdout+r.stderr
print('RED original bytecode reproduces nullable pull crash',flush=True)
subprocess.run([str(context.java),'-cp',toolcp,'WandTomsPatch','green',str(B/'original-handler.class'),str(B/'patched-handler.class'),str(green)],check=True)
r=compile_run(green);(B/'green-result.txt').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,r.stdout+r.stderr;print(r.stdout,flush=True)
artifact=B/'constructionwand-kots-1.21.1-2.16.9-toms-null-guard.1.jar';assert not artifact.exists()
with zipfile.ZipFile(ORIGINAL) as z:
 entries={name:z.read(name) for name in z.namelist()}
entries[ENTRY]=(B/'patched-handler.class').read_bytes()
for name in ('LICENSE','LICENSE-UPSTREAM-ConstructionWand.txt','NOTICE.md'):
 entries['META-INF/motiquies/'+name]=(P/name).read_bytes()
deterministic_archive(artifact,entries)
with zipfile.ZipFile(ORIGINAL) as z,zipfile.ZipFile(artifact) as w:
 assert w.testzip() is None and set(z.namelist())<=set(w.namelist())
 changed=[n for n in z.namelist() if z.read(n)!=w.read(n)];assert changed==[ENTRY]
 original_entry_count=len(z.namelist())
receipt={'original_sha256':PIN,'artifact':str(artifact),'sha256':hashlib.sha256(artifact.read_bytes()).hexdigest(),'bytes':artifact.stat().st_size,'changed_entries':changed,'other_entries_identical':original_entry_count-1,'unchanged_method_opcodes_audited':True,'actual_useItems_bytecode_red_green':True,'test_scope':'actual method bytecode; isolated terminal/world adapters; real game integration pending','production_modified':False}
(B/'receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(receipt,ensure_ascii=False),flush=True)

assert hashlib.sha256(ORIGINAL.read_bytes()).hexdigest()==PIN
overlay_receipt(context,artifact,receipt)
