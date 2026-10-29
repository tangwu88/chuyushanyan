# API 约定

所有业务接口位于 /api/v1。登录接口返回12小时 Bearer token；其他接口需要 Authorization: Bearer token。初次改密账户仅可访问 me、password、logout。

写操作需要 Idempotency-Key，8至100个字符。同一用户、操作及请求编号只能使用相同内容；成功重试返回相同结果，改变内容返回409。余额不足等业务约束返回422，未登录401，角色权限不足403。所有金额使用十进制字符串，最多两位小数，手机号和点位编号均为文本。

## 登录与基本数据

| 方法 | 地址                        | 功能                                              |
| ---- | --------------------------- | ------------------------------------------------- |
| POST | /auth/login                 | username、password 登录                           |
| GET  | /auth/me                    | 当前用户与权限                                    |
| POST | /auth/password              | oldPassword、newPassword、可选 newPaymentPassword |
| POST | /auth/logout                | 退出                                              |
| GET  | /config                     | 当前规则、转账开关、业务日期                      |
| GET  | /catalog                    | 上架商品及店铺                                    |
| GET  | /members/lookup?account=... | 精确查找点位或手机号，电话脱敏                    |
| GET  | /network/{id}?page=1        | 按层分页的推荐网络                                |

## 查询与导出

GET /lists/{resource}：资源为 members、orders、ledger、awards、withdrawals、topups、shipments、batches、audits、jobs、users。

参数包括 page、size、search、start、end、pointId、shopId、status、type。分页最大200。search 为精确匹配；会员支持点位、昵称、手机号。

订单 type=REPORT 表示新人+首购，REPEAT 表示复购；dateField=activatedAt 按激活时间筛选。ledger 支持 kind=REWARD/RECHARGE、direction=IN/OUT。

GET /exports/{resource} 使用相同筛选及数据权限，分批流式输出 XLSX，单次上限100万条。手机号保留前导零，用户文字不会转换成公式。

GET /reports?start=...&end=... 返回汇总、按奖金类型分组和每日业绩。业绩按激活日期，奖金按入账日期，拨比由总额相除。GET /reconciliation 核对借贷及余额。

## 写入操作

POST /{resource}：

| 资源              | 主要输入                                                                                 |
| ----------------- | ---------------------------------------------------------------------------------------- |
| members           | phone、nickname、parentId、subPoint、emptyPoint                                          |
| quotes / orders   | pointId、shopId、payerId、productId、type、quantity、rewardAmount、paymentMethod、pickup |
| transfers         | fromId、toId、amount、paymentPassword                                                    |
| conversions       | pointId、amount、paymentPassword                                                         |
| withdrawals       | pointId、amount、bankAccount、paymentPassword                                            |
| topups            | shopId、amount                                                                           |
| stock-transfers   | fromId、toId、amount；会员操作还需 paymentPassword                                       |
| funds-adjustments | pointId、kind、amount、reason                                                            |
| shipments         | shopId、pickup、recipient、phone、address、lines[{orderId,quantity}]                     |
| previews          | cycle=DAILY/MONTHLY/MONTHLY_REPEAT、period、可选 ruleId                                  |
| rules             | name、可选 config                                                                        |
| products          | 商品价格、月限购、店补、固定级差与重量                                                   |
| system-users      | username、displayName、phone、password、role                                             |

POST /{resource}/{id}/{action}：

| 资源                    | 动作                                              |
| ----------------------- | ------------------------------------------------- |
| orders                  | pay、activate、cancel（需要 reason）              |
| members                 | update、reset-password（需要 reason）             |
| withdrawals / topups    | review：approved、reason                          |
| shipments               | review：approved、reason；ship：carrier、tracking |
| rules                   | update：config、reason；test；publish             |
| batches                 | post                                              |
| settings/1              | update：transferEnabled、reason                   |
| products / system-users | update                                            |

GET /orders/{id} 返回独立支付/激活/发货状态、规则与关系快照、即时奖金、物流及轨迹；GET /shipments/{id} 返回申请及分配明细；GET /batches/{id} 返回预览/入账结果；GET /rules 返回版本；GET /scenarios 返回计算案例。

## 支付与扩展

paymentMethod=DEMO 为演示现金，STORE 为店铺充值积分。付款会预留本订单额度，首次/复购自动激活，新人由店铺按点位顺序激活。PaymentGateway 是真实支付的扩展边界，演示开关关闭时模拟确认不可用。

写操作由 Commands 提供事务、数据库锁和请求防重；领域服务继续校验权限与状态。RewardEngine 不访问数据库，预览和入账共用算法。已发布规则不可覆盖；账本通过追加流水冲正。
