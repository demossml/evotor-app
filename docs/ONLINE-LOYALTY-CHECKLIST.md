# HISTORICAL

Этот чеклист относится к старому standalone-cashier/enroll прототипу. Актуальный сценарий описан в `README.md` и `INSTALL.md`: Evotor Cloud catalog + local InventoryApi + signed QR/manual card code + server-side SELL loyalty.

# Касса онлайн — чеклист самопроверки

1. Enroll: publicKey 43 chars base64url Ed25519 (не placeholder).
2. Запросы кассы: заголовок X-Device-Token (sales, sync, staff).
3. Карта: только token с подписью serverPub; DEMO/FREE/CB отклоняются.
4. После «Открыть чек» лояльность НЕ уходит на сервер.
5. «Оплата прошла» → POST /api/devices/sales → новый card token на экране.
6. «Ещё заказ» без подтверждения — карта сбрасывается / нельзя со старым free.
7. Повторный скан той же карты < 20 с — отказ.
8. 401 → deviceRevoked, сообщение баристе.
9. Каталог: countsAsCup; рецепты с /directory/staff.
10. versionCode 30 / 2.0.0.
