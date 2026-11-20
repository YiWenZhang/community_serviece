package cn.community.notice;

import cn.community.common.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

/** HTTPS JSON adapter contract; a platform adapter must honor the stable Idempotency-Key. */
@Component
public class SmsGateway {
  private final Json json;

  public SmsGateway(Json json) {
    this.json = json;
  }

  public static URI endpoint(String value) {
    try {
      URI uri = URI.create(value);
      Problem.require(
          "https".equalsIgnoreCase(uri.getScheme())
              && uri.getHost() != null
              && uri.getUserInfo() == null
              && uri.getFragment() == null
              && uri.getQuery() == null,
          "短信地址须为不含凭据和查询串的HTTPS接口");
      for (InetAddress ip : InetAddress.getAllByName(uri.getHost()))
        Problem.require(
            !(ip.isAnyLocalAddress()
                || ip.isLoopbackAddress()
                || ip.isLinkLocalAddress()
                || ip.isSiteLocalAddress()
                || ip.isMulticastAddress()
                || (ip instanceof Inet6Address && (ip.getAddress()[0] & 0xfe) == 0xfc)),
            "短信地址不能指向本机或私有网络");
      return uri;
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      throw new Problem(422, "短信接口地址无效或无法解析");
    }
  }

  public String send(
      Map<String, Object> settings, String requestId, String phone, Map<String, Object> params) {
    var uri = endpoint(settings.get("endpoint").toString());
    var body =
        Db.p(
            "requestId",
            requestId,
            "to",
            phone,
            "signature",
            settings.get("signature"),
            "templateId",
            settings.get("templateId"),
            "parameters",
            params);
    var request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + settings.get("apiKey"))
            .header("Idempotency-Key", requestId)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.write(body)))
            .build();
    try {
      var response =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(8))
              .followRedirects(HttpClient.Redirect.NEVER)
              .build()
              .send(request, HttpResponse.BodyHandlers.ofString());
      return accepted(response.statusCode(), response.body());
    } catch (Problem e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Problem(422, "短信调用中断，将使用相同请求号重试");
    } catch (Exception e) {
      throw new Problem(422, "短信平台调用失败，将使用相同请求号重试；请检查平台日志");
    }
  }

  public String accepted(int status, String body) {
    Problem.require(status >= 200 && status < 300, "短信平台拒绝请求，请检查平台配置或额度");
    Problem.require(body != null && body.length() <= 65536, "短信平台响应无效或过大");
    try {
      var result = json.map(body);
      String id = Objects.toString(result.get("messageId"), "");
      Problem.require(
          Boolean.TRUE.equals(result.get("accepted")) && id.matches("[A-Za-z0-9_.:-]{1,100}"),
          "短信平台未确认受理，未记录发送成功");
      return id;
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      throw new Problem(422, "短信平台响应格式不符合约定");
    }
  }
}
