package cn.community.common;

/** 可公开的业务错误；禁止向客户端输出SQL、密钥和原始异常。 */
public class Problem extends RuntimeException {
  public final int status;

  public Problem(int status, String message) {
    super(message);
    this.status = status;
  }

  public static Problem missing() {
    return new Problem(404, "资源不存在或不可访问");
  }

  public static void require(boolean valid, String message) {
    if (!valid) throw new Problem(422, message);
  }

  public static void changed(int rows) {
    if (rows != 1) throw new Problem(409, "数据版本已变化，请刷新后重试");
  }
}
