package com.github.lechandonga.paymentclearingledgerjava.domain;

public enum AccountType {
    /** 付款人账户：余额可被授权占用 */
    PAYER,
    /** 商户账户：结算入账 */
    MERCHANT,
    /** 平台清算过渡户：按币种一个 */
    PLATFORM_CLEARING,
    /** 外部资金来源户：允许贷方余额为负（资金源头） */
    EXTERNAL
}
