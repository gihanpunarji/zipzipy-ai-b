package com.gihan.contextioq.ContextIQ.notification;
import com.gihan.contextioq.ContextIQ.notification.InvitationService.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {
    private final InvitationService service;
    public NotificationController(InvitationService service) { this.service=service; }
    @GetMapping("/notifications") public NotificationPage list(@RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset) { return service.notifications(offset); }
    @PostMapping("/notifications/read") @ResponseStatus(HttpStatus.NO_CONTENT) public void readAll() { service.readAll(); }
    @PostMapping("/notifications/{id}/respond") public InvitationView respond(@PathVariable UUID id,@Valid @RequestBody DecisionRequest request) { return service.respond(id,request.decision()); }
    @GetMapping("/organizations/{id}/invitations") public List<InvitationView> sent(@PathVariable UUID id) { return service.sent(id); }
    @DeleteMapping("/organizations/{id}/invitations/{invitation}") public InvitationView cancel(@PathVariable UUID id,@PathVariable UUID invitation) { return service.cancel(id,invitation); }
    @PostMapping("/organizations/{id}/invitations/{invitation}/email") public InvitationView retry(@PathVariable UUID id,@PathVariable UUID invitation) { return service.retryEmail(id,invitation); }
}
