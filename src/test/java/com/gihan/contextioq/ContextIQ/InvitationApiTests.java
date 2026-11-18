package com.gihan.contextioq.ContextIQ;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.gihan.contextioq.ContextIQ.auth.entity.AppUser;
import com.gihan.contextioq.ContextIQ.auth.repository.UserRepository;
import com.gihan.contextioq.ContextIQ.notification.InvitationEmailWorker;
import com.gihan.contextioq.ContextIQ.workspace.WorkspaceAccess;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class InvitationApiTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired WorkspaceAccess access;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    UUID admin, recipient, stranger, org;
    String email;
    @BeforeEach void setup() throws Exception {
        admin=account(); recipient=account(); stranger=account();
        email=users.findById(recipient).orElseThrow().getEmail();
        String name="Invites "+UUID.randomUUID();
        mvc.perform(post("/api/v1/organizations").with(user(admin.toString())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\""+name+"\"}")).andExpect(status().isCreated());
        org=jdbc.queryForObject("SELECT id FROM organizations WHERE name=?",UUID.class,name);
    }
    UUID account() { UUID id=users.save(new AppUser("Invite","Test",UUID.randomUUID()+"@example.com","test",null)).getId();access.ensurePersonal(id);return id; }
    UUID invite() throws Exception {
        mvc.perform(post("/api/v1/organizations/"+org+"/members").with(user(admin.toString())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\""+email+"\"}")).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.emailStatus").value("DISABLED"));
        return jdbc.queryForObject("SELECT id FROM organization_invitations WHERE organization_id=? ORDER BY created_at DESC LIMIT 1",UUID.class,org);
    }
    void respond(UUID id, UUID who, String decision, int expected) throws Exception {
        mvc.perform(post("/api/v1/notifications/"+id+"/respond").with(user(who.toString())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"decision\":\""+decision+"\"}")).andExpect(status().is(expected));
    }
    int membership() { return jdbc.queryForObject("SELECT COUNT(*) FROM organization_members WHERE organization_id=? AND user_id=? AND active=TRUE",Integer.class,org,recipient); }
    @Test void notificationIsAccountScopedAndAcceptanceIsExplicitAndIdempotent() throws Exception {
        UUID id=invite(); assertEquals(0,membership());
        mvc.perform(get("/api/v1/notifications").with(user(recipient.toString())).header("X-Workspace-Id",UUID.randomUUID()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id.toString())).andExpect(jsonPath("$.unreadCount").value(1));
        mvc.perform(get("/api/v1/notifications").with(user(stranger.toString())))
            .andExpect(jsonPath("$.items.length()").value(0));
        respond(id,stranger,"ACCEPT",404); assertEquals(0,membership());
        respond(id,recipient,"ACCEPT",200); respond(id,recipient,"ACCEPT",200); assertEquals(1,membership());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM workspace_members WHERE organization_id=? AND user_id=?",Integer.class,org,recipient));
        respond(id,recipient,"REJECT",409);
        mvc.perform(get("/api/v1/notifications").with(user(recipient.toString())))
            .andExpect(jsonPath("$.unreadCount").value(0)).andExpect(jsonPath("$.items[0].status").value("ACCEPTED"));
    }
    @Test void rejectExpireAndDuplicateHaveNoMembershipSideEffects() throws Exception {
        UUID id=invite();
        mvc.perform(post("/api/v1/organizations/"+org+"/members").with(user(admin.toString())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\""+email+"\"}")).andExpect(status().isConflict());
        respond(id,recipient,"REJECT",200);respond(id,recipient,"REJECT",200);respond(id,recipient,"ACCEPT",409);
        UUID newer=invite();
        jdbc.update("UPDATE organization_invitations SET expires_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),newer);
        respond(newer,recipient,"ACCEPT",410);
        mvc.perform(get("/api/v1/notifications").with(user(recipient.toString()))).andExpect(jsonPath("$.items[0].status").value("EXPIRED"));
        invite(); assertEquals(0,membership());
    }
    @Test void authorizationValidationAndReadState() throws Exception {
        UUID id=invite();
        mvc.perform(post("/api/v1/notifications/"+id+"/respond").with(user(recipient.toString())).contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"ACCEPT\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/notifications/"+id+"/respond").with(user(recipient.toString())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/notifications?offset=-1").with(user(recipient.toString()))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/organizations/"+org+"/invitations").with(user(stranger.toString()))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/notifications/read").with(user(stranger.toString())).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/notifications").with(user(recipient.toString()))).andExpect(jsonPath("$.unreadCount").value(1));
        mvc.perform(post("/api/v1/notifications/read").with(user(recipient.toString())).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/notifications").with(user(recipient.toString()))).andExpect(jsonPath("$.unreadCount").value(0));
        jdbc.update("UPDATE organization_members SET role='MEMBER' WHERE organization_id=? AND user_id=?",org,admin);
        respond(id,recipient,"ACCEPT",409); assertEquals(0,membership());
    }
    @Test void rejoiningNeverRestoresOldWorkspaceAccess() throws Exception {
        UUID id=invite();
        jdbc.update("INSERT INTO organization_members VALUES (?, ?, 'ADMIN', FALSE)",org,recipient);
        UUID ws=UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces(id,organization_id,name,description,created_by,created_at,personal,deleted) VALUES (?, ?, 'Old team', '', ?, CURRENT_TIMESTAMP,FALSE,FALSE)",ws,org,admin);
        jdbc.update("INSERT INTO workspace_members VALUES (?, ?, ?, 'ADMIN', TRUE, ?)",ws,org,recipient,UUID.randomUUID());
        respond(id,recipient,"ACCEPT",200);
        assertEquals("MEMBER",jdbc.queryForObject("SELECT role FROM organization_members WHERE organization_id=? AND user_id=?",String.class,org,recipient));
        assertFalse(jdbc.queryForObject("SELECT active FROM workspace_members WHERE workspace_id=? AND user_id=?",Boolean.class,ws,recipient));
    }
    @Test void durableEmailDeliveryUsesJavaMailSenderAndHonorsResponses() throws Exception {
        UUID id=invite(); JavaMailSender sender=mock(JavaMailSender.class);
        var worker=new InvitationEmailWorker(jdbc,sender,transactions,true,false,"no-reply@example.com","http://localhost:5173");
        jdbc.update("UPDATE organization_invitations SET email_status='QUEUED' WHERE id=?",id);
        new InvitationEmailWorker(jdbc,sender,transactions,false,false,"","http://localhost:5173").dispatchPending();
        verifyNoInteractions(sender); // Disabled SMTP must not consume queued work.
        worker.dispatchPending();worker.dispatchPending();
        var captor=ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender,times(1)).send(captor.capture());
        assertEquals(email,captor.getValue().getTo()[0]);
        assertTrue(captor.getValue().getText().contains("/workspaces?notifications=open"));
        assertEquals("SENT",jdbc.queryForObject("SELECT email_status FROM organization_invitations WHERE id=?",String.class,id));
        assertEquals(0,membership());respond(id,recipient,"REJECT",200);
        UUID next=invite();jdbc.update("UPDATE organization_invitations SET email_status='QUEUED' WHERE id=?",next);
        respond(next,recipient,"ACCEPT",200);worker.dispatchPending();
        verifyNoMoreInteractions(sender);
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT email_status FROM organization_invitations WHERE id=?",String.class,next));
    }
    @Test void cancelledInvitationsCannotBeAcceptedOrEmailedAndCanBeReissued() throws Exception {
        UUID id=invite();
        String endpoint="/api/v1/organizations/"+org+"/invitations/"+id;
        mvc.perform(delete(endpoint).with(user(stranger.toString())).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete(endpoint).with(user(admin.toString())).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(delete(endpoint).with(user(admin.toString())).with(csrf())).andExpect(status().isOk());
        respond(id,recipient,"ACCEPT",409);
        mvc.perform(get("/api/v1/notifications").with(user(recipient.toString()))).andExpect(jsonPath("$.items[0].status").value("CANCELLED"));
        JavaMailSender sender=mock(JavaMailSender.class);
        new InvitationEmailWorker(jdbc,sender,transactions,true,false,"no-reply@example.com","http://localhost:5173").dispatchPending();
        verifyNoInteractions(sender);
        assertNotEquals(id,invite());assertEquals(0,membership());
    }
    @Test void demotingInviterCancelsPendingInvitationsWithoutBlockingReplacementInvites() throws Exception {
        UUID id=invite();
        jdbc.update("INSERT INTO organization_members VALUES (?, ?, 'ADMIN', TRUE)",org,stranger);
        mvc.perform(patch("/api/v1/organizations/"+org+"/members/"+admin).with(user(stranger.toString())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MEMBER\"}"))
            .andExpect(status().isNoContent());
        respond(id,recipient,"ACCEPT",409);
        mvc.perform(get("/api/v1/notifications").with(user(recipient.toString()))).andExpect(jsonPath("$.items[0].status").value("CANCELLED"));
        mvc.perform(post("/api/v1/organizations/"+org+"/members").with(user(stranger.toString())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\"}"))
            .andExpect(status().isCreated());
    }
    @Test void retryEmailRequiresOrganizationAdminAndConfiguredMail() throws Exception {
        UUID id=invite();
        mvc.perform(post("/api/v1/organizations/"+org+"/invitations/"+id+"/email").with(user(recipient.toString())).with(csrf()))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/organizations/"+org+"/invitations/"+id+"/email").with(user(admin.toString())).with(csrf()))
            .andExpect(status().isConflict());
        assertEquals("DISABLED",jdbc.queryForObject("SELECT email_status FROM organization_invitations WHERE id=?",String.class,id));
    }
    @Test void smtpFailureIsRetriedOnlyThreeTimesWithoutLosingInvitation() throws Exception {
        UUID id=invite();JavaMailSender sender=mock(JavaMailSender.class);
        doThrow(new MailSendException("Simulated failure")).when(sender).send(any(SimpleMailMessage.class));
        var worker=new InvitationEmailWorker(jdbc,sender,transactions,true,false,"no-reply@example.com","http://localhost:5173");
        jdbc.update("UPDATE organization_invitations SET email_status='QUEUED' WHERE id=?",id);
        for(int i=0;i<4;i++) {
            jdbc.update("UPDATE organization_invitations SET email_next_attempt=CURRENT_TIMESTAMP WHERE id=?",id);
            worker.dispatchPending();
        }
        verify(sender,times(3)).send(any(SimpleMailMessage.class));
        assertEquals("FAILED",jdbc.queryForObject("SELECT email_status FROM organization_invitations WHERE id=?",String.class,id));
        respond(id,recipient,"ACCEPT",200);assertEquals(1,membership());
    }
}
