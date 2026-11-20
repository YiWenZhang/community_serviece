package cn.community.notice;

import cn.community.common.Api;
import cn.community.identity.Auth;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class SmsController {
  private final Auth auth;
  private final SmsService sms;

  public SmsController(Auth auth, SmsService sms) {
    this.auth = auth;
    this.sms = sms;
  }

  @GetMapping("/sms/settings")
  public Object settings() {
    return Api.ok(sms.settings(auth.current()));
  }

  @PutMapping("/sms/settings")
  public Object save(@Valid @RequestBody SmsService.Settings b) {
    return Api.ok(sms.save(auth.current(), b));
  }

  @PostMapping("/notice-recipients/{id}/sms")
  public Object send(
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody NoticeService.Version b) {
    return Api.ok(sms.enqueue(auth.current(), id, key, b.version()));
  }

  @GetMapping("/notice-recipients/{id}/sms")
  public Object history(@PathVariable long id) {
    return Api.ok(sms.history(auth.current(), id));
  }
}
