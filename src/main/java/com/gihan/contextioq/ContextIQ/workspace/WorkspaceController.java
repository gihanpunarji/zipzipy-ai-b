package com.gihan.contextioq.ContextIQ.workspace;
import com.gihan.contextioq.ContextIQ.workspace.WorkspaceService.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {
    private final WorkspaceService service;
    private final OrganizationMemberService organizationMembers;
    private final com.gihan.contextioq.ContextIQ.notification.InvitationService invitations;
    public WorkspaceController(WorkspaceService service,com.gihan.contextioq.ContextIQ.notification.InvitationService invitations,OrganizationMemberService organizationMembers) { this.service=service; this.invitations=invitations; this.organizationMembers=organizationMembers; }
    @GetMapping("/organizations") public List<OrganizationView> organizations() { return service.organizations(); }
    @PostMapping("/organizations") @ResponseStatus(HttpStatus.CREATED) public OrganizationView createOrganization(@Valid @RequestBody NameRequest r) { return service.createOrganization(r); }
    @GetMapping("/organizations/{id}/members") public List<OrganizationMemberService.MemberView> organizationMembers(@PathVariable UUID id) { return organizationMembers.list(id); }
    @PatchMapping("/organizations/{id}/members/{user}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeOrganizationRole(@PathVariable UUID id,@PathVariable UUID user,@Valid @RequestBody OrganizationMemberService.RoleRequest request) { organizationMembers.changeRole(id,user,request.role()); }
    @DeleteMapping("/organizations/{id}/members/{user}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeOrganizationMember(@PathVariable UUID id,@PathVariable UUID user) { organizationMembers.remove(id,user); }
    @PostMapping("/organizations/{id}/members") @ResponseStatus(HttpStatus.CREATED) public com.gihan.contextioq.ContextIQ.notification.InvitationService.InvitationView invite(@PathVariable UUID id,@Valid @RequestBody OrganizationMemberRequest r) { return invitations.invite(id,r.email()); }
    @PostMapping("/organizations/{id}/workspaces") @ResponseStatus(HttpStatus.CREATED) public WorkspaceView create(@PathVariable UUID id,@Valid @RequestBody WorkspaceRequest r) { return service.create(id,r); }
    @GetMapping("/workspaces") public List<WorkspaceView> list() { return service.list(); }
    @GetMapping("/workspaces/{id}") public WorkspaceView get(@PathVariable UUID id) { return service.get(id); }
    @PostMapping("/workspaces/{id}") public WorkspaceView update(@PathVariable UUID id,@Valid @RequestBody WorkspaceRequest r) { return service.update(id,r); }
    @DeleteMapping("/workspaces/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id) { service.delete(id); }
    @GetMapping("/workspaces/{id}/members") public List<MemberView> members(@PathVariable UUID id) { return service.members(id); }
    @PostMapping("/workspaces/{id}/members") @ResponseStatus(HttpStatus.NO_CONTENT) public void member(@PathVariable UUID id,@Valid @RequestBody MemberRequest r) { service.setMember(id,r); }
    @DeleteMapping("/workspaces/{id}/members/{user}") @ResponseStatus(HttpStatus.NO_CONTENT) public void remove(@PathVariable UUID id,@PathVariable UUID user) { service.removeMember(id,user); }
}
