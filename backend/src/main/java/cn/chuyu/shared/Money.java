package cn.chuyu.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
  public static final BigDecimal ZERO = new BigDecimal("0.00");

  private Money() {}

  public static BigDecimal d(Object value) {
    return value == null ? ZERO : new BigDecimal(value.toString());
  }

  public static BigDecimal money(Object value) {
    return d(value).setScale(2, RoundingMode.HALF_UP);
  }

  public static BigDecimal input(Object value) {
    BigDecimal n;
    try {
      n = d(value);
    } catch (RuntimeException e) {
      throw new BusinessException("请输入正确金额");
    }
    BusinessException.require(
        n.scale() <= 2 && n.compareTo(new BigDecimal("1000000000000000000")) < 0 && n.signum() > 0,
        "金额必须大于零、少于10的18次方，且最多两位小数");
    return money(n);
  }

  public static BigDecimal max0(BigDecimal n) {
    return n.max(ZERO);
  }
}
