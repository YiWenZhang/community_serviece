package cn.community.ai;

import cn.community.common.Crypto;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Embeddings {
  private final AiClient client;
  private final Crypto crypto;

  public Embeddings(AiClient client, Crypto crypto) {
    this.client = client;
    this.crypto = crypto;
  }

  public String space(AiModels.Provider p) {
    return p == null
        ? "local-zh-hash-v1"
        : crypto.digest(p.id() + ":" + p.baseUrl() + ":" + p.model());
  }

  public float[] encode(AiModels.Provider p, String text) {
    return p == null ? local(text) : client.embed(p, text);
  }

  /** 离线中文字符1/2/3-gram特征哈希；无额外密钥，不冒充神经网络语义模型。 */
  public static float[] local(String text) {
    String s = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    float[] v = new float[768];
    for (int n = 1; n <= 3; n++)
      for (int i = 0; i + n <= s.length(); i++) {
        int h = s.substring(i, i + n).hashCode();
        v[Math.floorMod(h, v.length)] += n == 1 ? 0.3f : 1f;
      }
    double norm = 0;
    for (float x : v) norm += x * x;
    if (norm == 0) {
      v[0] = 1;
      return v;
    }
    for (int i = 0; i < v.length; i++) v[i] /= (float) Math.sqrt(norm);
    return v;
  }
}
