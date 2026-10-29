package cn.chuyu.orders;

import cn.chuyu.shared.BusinessException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Production adapters verify provider signatures and amounts before confirmation. */
public interface PaymentGateway {
  void confirm(String orderId);

  @Component
  class Demo implements PaymentGateway {
    private final boolean demo;

    public Demo(Environment env) {
      this.demo = env.getProperty("chuyu.demo", Boolean.class, false);
    }

    public void confirm(String orderId) {
      BusinessException.require(demo, "当前支付通道尚未开通");
    }
  }
}
