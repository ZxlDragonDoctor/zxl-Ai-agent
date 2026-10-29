package com.zxl.zxlaiagent.skills;

import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.web.AuthenticatedRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.function.Supplier;

/** Authenticated reviewed catalogue; HTTP never publishes or modifies definitions. */
@RestController
@RequestMapping("/ai/learning/skills")
@ConditionalOnProperty(name="agent.security.mode",havingValue="required")
public final class FormalSkillController {
    private final SkillRegistry registry;
    private final AuthorizationService authorization;
    public FormalSkillController(SkillRegistry registry,AuthorizationService authorization){this.registry=registry;this.authorization=authorization;}
    @GetMapping public SkillController.Directory list(HttpServletRequest request){
        return publish(request,()->new SkillController.Directory(registry.defaultVersion("it-ticket-analysis"),
                registry.list().stream().filter(v->formal(v.definition())).toList(),false));
    }
    @GetMapping("/{id}/{version}") public SkillRegistry.View view(HttpServletRequest request,@PathVariable String id,@PathVariable String version){
        return publish(request,()->{
            try{var view=registry.view(id,version);if(!formal(view.definition()))throw new IllegalArgumentException();return view;}
            catch(IllegalArgumentException missing){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"SKILL_NOT_FOUND");}
        });
    }
    private <T>T publish(HttpServletRequest request,Supplier<T> operation){
        var actor=AuthenticatedRequest.require(request).ref();
        return authorization.gateAll(actor,List.of(new AuthorizationService.Permission("skill.execute",null),
                new AuthorizationService.Permission("agent.execute",null),new AuthorizationService.Permission("knowledge.read",null)),Phase.RESULT_PUBLISH,null,operation);
    }
    static boolean formal(SkillDefinition definition){return List.of("v3","v4").contains(definition.version())&&definition.tools().equals(List.of(SkillDefinition.formalTool()));}
}
