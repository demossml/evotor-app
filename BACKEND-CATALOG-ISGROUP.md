# Backend: отдать isGroup в GET /api/devices/catalog

После миграции 027 в выборку products добавьте колонки и флаги:

```sql
COALESCE(p.is_group, 0) AS isGroup,
p.parent_product_id AS parentProductId
```

В JSON каждого product:

```js
isGroup: row.isGroup === 1,
parentProductId: row.parentProductId ?? null,
hideFromMenu: modifierProductIds.has(id) || row.isGroup === 1,
isModifier: modifierProductIds.has(id),
```

Либо фильтруйте `WHERE COALESCE(p.is_group,0)=0` из витринных products (группы тогда только в Evotor inventory как папки).

Минимальный патч в `devices/routes.ts` catalog handler — без смены контракта toppings/upsell.
