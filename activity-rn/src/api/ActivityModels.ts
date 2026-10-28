// 活动业务投影：字段由 Spring 的任务快照、支付统计和金币账本产生，不含令牌或用户标识。
export type TaskAction = 'LOGIN' | 'ACCEPT' | 'COMPLETE' | 'CLAIM' | 'DETAIL';
export type TaskStatus = 'AVAILABLE' | 'IN_PROGRESS' | 'READY' | 'CLAIMED' | 'REVERSED' | 'INTERRUPTED' | 'EXPIRED' | 'CLAIM_EXPIRED';
/** 自然日进度只用于显示，日期和是否完成均以服务端结果为准。 */
export type ActivityDay = {date: string; count: number; target: number; completed: boolean};
/** 领取前没有 instanceId；领取后保留不可变规则快照和服务端截止。 */
export type ActivityTask = {
  definitionId: string; ruleVersion: number; instanceId?: string; title: string;
  type: 'DAILY_ORDER_COUNT' | 'CONSECUTIVE_ORDER_DAYS'; targetCount: number; dayCount: number;
  rewardCoins: number; status: TaskStatus; allowedActions: TaskAction[]; reasonCode?: string | null;
  completed?: number; required?: number; days?: ActivityDay[];
  acceptedAt?: string; windowEnd?: string; claimDeadline?: string; serverTime?: string; receipt?: RewardReceipt;
};
/** 唯一奖励回执；REVERSED 不能当成新到账成功，也不能再次发放。 */
export type RewardReceipt = {receiptId: string; instanceId: string; amount: number; balance: number; status: 'CLAIMED' | 'REVERSED'; createdAt: string};
/** recoveredTask 仅在带原请求键的本人只读核查中返回，不按当前周期猜测旧写结果。 */
export type TaskList = {tasks: ActivityTask[]; serverTime: string; recoveredTask?: ActivityTask | null};
export type CoinAccount = {balance: number; serverTime: string};
/** 流水不可被UI删除或合并；冲正与原奖励都须展示。 */
export type CoinTransaction = {transactionId: string; instanceId: string; title: string; kind: 'REWARD' | 'REVERSAL'; amount: number; createdAt: string};
export type CoinPage = {items: CoinTransaction[]; nextCursor: string | null; serverTime: string};
