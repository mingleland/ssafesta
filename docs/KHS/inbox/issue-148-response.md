출처 확인 결과 공유드립니다. **ExpoKit은 로컬 정리 이름이고, 원본 상품은 Stand Expo Pack(114974)입니다. Laptop은 Laptop Free(90315)입니다.** 사용 중인 에셋 링크를 담당자가 제공했고, 두 팩의 상품 ID·이름은 develop의 `.meta`에도 남아 있어 교차 확인했습니다.

## 요청하신 3팩

| 에셋/Prefab 또는 경로 | 원본 팩 / 퍼블리셔 | 출처 | 무료/유료 | 확인된 라이선스 | 확인 근거 |
|---|---|---|---|---|---|
| `Art/Booth/ExpoKit` — BoothShell 및 부스 오브젝트의 ExpoKit 부분 | **Stand Expo Pack** / AndragorInc | [Unity Asset Store · 114974](https://assetstore.unity.com/packages/3d/props/interior/stand-expo-pack-114974) | 유료 상품. 현재 공개 표시 $20이며 실제 취득 금액은 별도 | 상품 페이지에 **Standard Unity Asset Store EULA** 명시 | 담당자 제공 링크 + 아래 `AssetOrigin` + 공식 상품 페이지 |
| `ithappy/Casino_Free` — AiAgent 및 Frame 등 | **Casino FREE - Low Poly Asset Pack** / ithappy | [Unity Asset Store · 393074](https://assetstore.unity.com/packages/3d/environments/casino-free-low-poly-asset-pack-393074) | 무료(FREE 표시) | 상품 페이지에 **Standard Unity Asset Store EULA** 명시 | 담당자 제공 링크 + `.meta`의 productId 393074/packageName + 공식 상품 페이지 |
| `Models/Laptop` — Laptop | **Laptop Free** / 퍼블리셔 미확인 | [Unity Asset Store · 90315](https://assetstore.unity.com/packages/package/90315) | 상품명은 Free. 현재 상품 페이지 가격은 미확인 | **개별 상품의 라이선스 표기는 아직 미확인**. `licenseType: Store`만으로 Standard EULA 적용 확정은 하지 않음 | 담당자 제공 링크 + 아래 `AssetOrigin`. 공개 링크 조회가 로그인으로 이동해 상품별 약관·별도 조건을 확인하지 못함 |

공개 페이지 확인일은 **2026-09-08**입니다. 상품 페이지와 메타데이터는 **출처·상품 약관을 확인하는 근거**이고, 구매 계정·주문번호·취득일·구매한 Entity/seat 범위까지 증명하는 자료는 이번 확인에 포함되지 않았습니다.

## 저장소에 남아 있는 직접 근거

develop `a3ec3aee98bc1d74b2fd25abf66f091eed2e619a` 기준으로 확인했습니다.

[Counter01.FBX.meta](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/blob/a3ec3aee98bc1d74b2fd25abf66f091eed2e619a/festa-unity/Assets/_Project/Art/Booth/ExpoKit/Models/Furniture/Counter01.FBX.meta):

```yaml
AssetOrigin:
  productId: 114974
  packageName: Stand Expo Pack
  packageVersion: 1.0
  assetPath: Assets/StandExpoPack/Models/Furniture/Counter01.FBX
  uploadId: 237227
```

[laptop.FBX.meta](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/blob/a3ec3aee98bc1d74b2fd25abf66f091eed2e619a/festa-unity/Assets/_Project/Models/Laptop/laptop.FBX.meta):

```yaml
AssetOrigin:
  productId: 90315
  packageName: Laptop Free
  packageVersion: 1.0
  assetPath: Assets/Laptop/laptop.FBX
  uploadId: 179394
```

따라서 “별도 라이선스 파일 없음”은 맞지만, **원본 팩을 식별할 메타데이터까지 없는 상태는 아닙니다.** `ExpoKit`의 출처와 `Laptop`의 상품 매핑에는 이 근거를 사용하실 수 있습니다.

## 함께 전달받은 나머지 사용 에셋

#148의 3팩과 혼동하지 않도록 별도로 남깁니다.

- [Carnival & Funfair Stalls - Game Booths, Food Carts, Circus & Festival Props · 398112](https://assetstore.unity.com/packages/3d/props/exterior/carnival-funfair-stalls-game-booths-food-carts-circus-festival-p-398112): 3S 3DreaMax Studio, 유료 상품(현재 $40 표시), Standard Unity Asset Store EULA. 이슈 본문의 #146 범위 판정대로 월드 축제존용이며 이번 부스 3팩 판정에 합치지 않습니다.
- [DS Arcade Machine · 121654](https://assetstore.unity.com/packages/3d/props/electronics/ds-arcade-machine-121654): DAN SANDERSON, 무료, Standard Unity Asset Store EULA. 이슈 본문에서 부스 의존 경로에 없다고 확인한 `danthaigames` 관련 참고 출처입니다.

## FE가 대조할 약관과 남은 확인

[Unity Asset Store Terms of Service and EULA](https://unity.com/legal/as-terms)의 Appendix 1에서 다음 조항을 대조할 수 있습니다.

- **2.2.1(a)(b)(e)**: 조건을 충족하는 제품에 에셋을 포함해 배포하는 권리와 그 목적의 수정.
- **2.2.1.1(b)(c)(d)**: 사용자의 에셋 상업적 유통, UGC 제작이 주목적인 제품에서의 에셋 수익화, 명시된 범위 밖 사용에 관한 제한.
- **2.2.2**: Restricted Asset의 별도 조건.

이는 **“GLB로 바꾸면 무조건 웹 배포 가능”이라는 승인 답변은 아닙니다.** Booth Studio의 실제 제공 방식이 제품 내 사용인지, 재사용 가능한 에셋 제공인지 등을 FE에서 위 조항과 대조해 주세요. Laptop은 상품별 약관 확인이 더 필요합니다. 이번 회신에서는 `source-packs.lock.json`의 정책을 변경하지 않았습니다.

마지막으로 **006 T016의 기존 체크를 GLB 서비스 배포 허용이나 구매 증빙의 근거로 사용하지 말아 주세요.** 이번에 확인한 사실은 에셋 확보·상품 매핑과 위 공개 약관입니다. 당시 별도 라이선스 검토가 있었다는 기록은 확인하지 못했으므로, 해당 문구는 “에셋 확보 완료 / 서비스 사용 조건 검토 별도”로 구분하는 것이 맞습니다. 이번 댓글로 검토 완료나 이슈 종료를 선언하지 않습니다.
