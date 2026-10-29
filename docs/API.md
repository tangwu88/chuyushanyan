# API 约定

所有业务接口位于 /api/v1。登录接口返回12小时 Bearer token；除登录及公开内容外需要 Authorization: Bearer token。初次改密账户仅可访问 me、password、logout及公开内容。连续五次错误密码会锁定15分钟，可由有权限的后台用户解锁。

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
| GET  | /members/{id}              | 当前权限范围内的会员完整资料                      |
| GET  | /content                   | 有效轮播、消息、协议和背景色，允许未登录读取        |

## 查询与导出

GET /lists/{resource}：核心资源为 members、orders、ledger、awards、withdrawals、topups、shipments、batches、audits、jobs、users。扩展资源为 products、empty-points、invited-members、referrals、promotions、director-promotions、cancellations、sync-upgrades、stock-transfers、unused-recharge、recharge-records、login-logs、report-snapshots、rankings、shop-stats、shop-cash、product-sales、daily-stats。

参数包括 page、size、search、start、end、pointId、shopId、status、type。分页最大200。search 为精确匹配；会员支持点位、昵称、手机号。

订单 type=REPORT 表示新人+首购，REPEAT 表示复购；dateField=activatedAt 按激活时间筛选。ledger 支持 kind=REWARD/RECHARGE、direction=IN/OUT。

orders支持payerId、available、includeCancelled；available用于分页查询可申请发货订单。includeArchived查询归档记录，topups的deleted查询伪删除申请。recharge-records的status=WAITING/SHIPPED按关联订单的履约状态筛选，全部记录不传status。统计资源日期不传时使用本月截至今日，排名以end为快照日期。

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
| withdrawals       | pointId、amount、bankAccount、paymentPassword，可选bankName、accountName、identityNumber |
| topups            | shopId、amount                                                                           |
| stock-transfers   | fromId、toId、amount；会员操作还需 paymentPassword                                       |
| funds-adjustments | pointId、kind、amount、reason                                                            |
| shipping-quotes / shipments | shopId、pickup、recipient、phone、address、lines[{orderId,quantity}]；提交可带expectedFreight防止过期报价 |
| previews          | cycle=DAILY/MONTHLY/MONTHLY_REPEAT、period、可选 ruleId                                  |
| rules             | name、可选 config                                                                        |
| products          | 商品价格、月限购、店补、固定级差、重量、channel、purchaseTypes、imageUrl、specification |
| system-users      | username、displayName、phone、password、role、department，可选postCode、email、gender |

members、orders可带remark（最多1000字）。后台会员update支持department变更，但更改所属部门另需全局system:write权限。手机号归并另需mergeConfirmed，冻结已有下级的点位需confirmDescendants。

POST /{resource}/{id}/{action}：

| 资源                    | 动作                                              |
| ----------------------- | ------------------------------------------------- |
| orders                  | pay、activate、cancel（需要 reason）              |
| members                 | update、reset-password、activate-empty（需要 reason） |
| cancellations           | restore（恢复冻结点位，不回写旧余额）              |
| withdrawals / topups    | review：approved、reason                          |
| topups                  | delete、restore（仅待审批申请）                   |
| shipments               | review：approved、reason；ship：carrier、tracking |
| rules                   | update：config、reason；test；publish             |
| batches                 | post                                              |
| settings/1              | update：transferEnabled、reason                   |
| products                | update、delete（逻辑删除）                        |
| system-users            | update、reset-password、unlock、delete（停用并撤销登录） |

GET /orders/{id} 返回独立支付/激活/发货状态、可见奖金、物流与操作轨迹。全量计算快照仅向具有全局财务读取权限的后台用户返回；会员只能看到本人点位所得奖金，发货角色不返回奖金。GET /shipments/{id} 返回application和lines；GET /batches/{id}需全局funds:read；GET /rules返回版本；GET /rules/compare?left=...&right=...返回参数及同案例金额对照；GET /scenarios返回计算案例；GET /funds/trace/{id}按权限返回订单、批次或事务相关流水与奖金。

## 配置、任务与管理

GET /management/{resource}查询配置；POST /management/{resource}新增；POST /management/{resource}/{id}/update或delete编辑、删除。资源为roles、departments、posts、menus、freights、announcements、banners、agreements、appearance、parameters、job-definitions。配置写入需要相应全局权限；已关联角色、部门和岗位有删除保护。图片接受http(s)链接或不超过2MB的PNG/JPEG/WebP数据。

POST /jobs/{id}/run：period为已结束业务日YYYY-MM-DD；月结任务为YYYY-MM。支持DAILY、MONTHLY、MONTHLY_REPEAT、MEMBERS、UPGRADES、PERFORMANCE、RANKING、FINANCE、ARCHIVE、RECONCILIATION。失败记录独立保留，重试不重复入账。

POST /sync-upgrades：fromPoint、targetPoint、expectedGrade、reason；POST /sync-upgrades/{id}/review：approved、reason。审批不能绕过实际升级条件。

POST /system-users/import：rows数组，最多500行，原子导入；phone作为文本处理，初始密码为后六位，首次登录强制修改。POST /logs/{resource}/{id}/delete逻辑删除审计或登录日志，id=all时清理全部，必须填写reason。

角色权限与数据范围独立：ALL、DEPARTMENT、SELF。系统配置、规则发布及全局结算需要ALL；普通业务同时执行点位范围校验。部门店铺可以办理其他部门客户订单。密码、令牌、原始银行卡和证件号不写入业务审计，导出和失败操作也留痕。

## 支付与扩展

paymentMethod=DEMO 为演示现金，STORE 为店铺充值积分。付款会预留本订单额度，首次/复购自动激活，新人由店铺按点位顺序激活。PaymentGateway 是真实支付的扩展边界，演示开关关闭时模拟确认不可用。

写操作由 Commands 提供事务、数据库锁和请求防重；领域服务继续校验权限与状态。RewardEngine 不访问数据库，预览和入账共用算法。已发布规则不可覆盖；账本通过追加流水冲正。
