-- 팔레트에서 빠진 남성복 3종의 판매를 멈춘다 (GitLab #120, S15P21A604-424).
--
-- 게임 파트가 남성 팔레트에서 여성복 3종을 뺐는데(S15P21A604-355), 뺀 방식이
-- AvatarCatalog.asset 의 items 배열에서 참조만 제거한 것이다. .asset 파일은 디스크에 남아 있고
-- V16 시드의 97행도 그대로다. 그래서 살 수는 있고 입을 수는 없는 상품 3종이 생겼다 —
-- 카탈로그가 onSale true 로 내려주고 구매가 201 로 성공해 코인이 빠지는데, Unity 가 그리지
-- 않아 착용할 화면이 없다.
--
-- is_on_sale FALSE 는 계약 §2-1 표 4행(미보유 · 판매 중지 → 잠금, 구매 버튼 없음)에 그대로
-- 들어간다. 새 오류 코드도 계약 변경도 없다 — InventoryService.purchase 가 이미 이 칸을 보고
-- ITEM_NOT_ON_SALE(409) 로 거절한다.
--
-- 행을 지우지 않는 이유: catalog_items.id 는 user_inventory_items 와 coin_ledger_entries 가
-- 참조한다. 이미 산 계정이 있으면 삭제는 그 기록을 깨뜨린다. 판매만 멈추는 것이 되돌리기도
-- 쉽다 — 팔레트에 다시 넣기로 하면 같은 한 줄을 TRUE 로 돌리면 된다.
--
-- 이미 산 계정은 이 UPDATE 로 구제되지 않는다. 보유는 유지되고 착용만 불가한 상태로 남는다.
-- dev · demo 구매 이력 확인은 GitLab #120 에서 게임 파트에 요청했다.

UPDATE catalog_items
   SET is_on_sale = FALSE
 WHERE item_type = 'AVATAR_PART'
   AND asset_key IN (
       '1814256283',  -- M_Bot.03
       '356384796',   -- M_Top.07-A
       '415781343'    -- M_Outfit.03
   );
