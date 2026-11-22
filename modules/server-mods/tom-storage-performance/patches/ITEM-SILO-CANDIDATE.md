# Item Silo controller identity — Beta3 candidate

The Create: Connected 1.3.3 Item Silo is a vertical multiblock inventory. Tom's Storage perf5.2 previously recognized the Create Item Vault controller for inventory identity. The patch recognizes Create: Connected's Item Silo controller through the same optional code path and adds no hard dependency.

The base file is `src/main/java/com/tom/storagemod/inventory/PlatformInventoryAccess.java`, SHA-256 `93355edca57ccfb77d287d131b3777d979575f9c5cfbc9c89f750ec154ca02bd`. Apply [the patch](item-silo-controller-dedup.patch) from the module root of a separate build tree. The module's historical compiler and checksum locks still target the historical baseline. Tom's Storage source and original MIT attribution are documented in the module's [LICENSE](../LICENSE) and [NOTICE](../NOTICE.md).

## Isolated server verification

| Inventory | Base connector slots | Candidate connector slots |
| --- | ---: | ---: |
| One Create vault | 20 | 20 |
| One Item Silo, attached on the side | 20 | 20 |
| One Item Silo, attached on top | 20 | 20 |
| Two-member Create vault | 40 | 40 |
| Two-member Item Silo | 80 | 40 |

Each case inserted and extracted 17 copper ingots via the merged Tom's Storage handler. The measured count was 17 after insertion and zero after extraction. The test ran against the exact Beta3 configuration on a loopback server with a synthetic world.

- Input Tom's Storage 2.4.1-perf5.2 SHA-256 `35851f83707f11afa52113f8424e3c99c1d67ebc1e2f76b8ea938dc7be6ab740`.
- Local QA-only candidate SHA-256 `93a217826643328832b8ea70e115a1e7b7ecc6f2cd95bf29143d1db14c33c474`. The comparison permits only generated `PlatformInventoryAccess` class entries to differ. All base resources were unchanged.
- Status is isolated server validation. Packaging and deployment approval remain separate. The candidate binary is stored outside Git, pending review of full asset redistribution.
