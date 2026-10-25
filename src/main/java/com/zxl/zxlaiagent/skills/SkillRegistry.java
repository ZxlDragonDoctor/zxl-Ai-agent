package com.zxl.zxlaiagent.skills;

import java.util.*;

/** Atomic in-process publication pointers; existing definitions are immutable and never rewritten. */
public final class SkillRegistry {
    public record View(SkillDefinition definition,boolean enabled,boolean isDefault) { }
    private record Key(String id,String version) { }
    private final Map<Key,SkillDefinition> definitions=new LinkedHashMap<>();
    private final Map<Key,Boolean> enabled=new HashMap<>();
    private final Map<String,String> defaults=new HashMap<>();
    public SkillRegistry(List<SkillDefinition> initial){if(initial==null||initial.isEmpty()||initial.size()>32)throw new IllegalArgumentException("SKILL_REGISTRY_SIZE");initial.forEach(this::register);}
    public synchronized void register(SkillDefinition definition){
        Objects.requireNonNull(definition);var key=new Key(definition.skillId(),definition.version());var existing=definitions.get(key);
        if(existing!=null){if(!existing.sha256().equals(definition.sha256()))throw new IllegalArgumentException("SKILL_VERSION_CONFLICT");return;}
        if(definitions.size()>=32)throw new IllegalArgumentException("SKILL_REGISTRY_SIZE");
        definitions.put(key,definition);enabled.put(key,"ACTIVE".equals(definition.state()));
        if("ACTIVE".equals(definition.state()))defaults.putIfAbsent(definition.skillId(),definition.version());
    }
    public synchronized List<View> list(){return definitions.keySet().stream().map(k->view(k.id,k.version)).toList();}
    public synchronized String defaultVersion(String id){String version=defaults.get(id);if(version==null)throw new IllegalArgumentException("SKILL_VERSION_NOT_FOUND");return version;}
    public synchronized View view(String id,String version){var key=new Key(id,version);var definition=definitions.get(key);if(definition==null)throw new IllegalArgumentException("SKILL_VERSION_NOT_FOUND");return new View(definition,enabled.get(key),Objects.equals(defaults.get(id),version));}
    public synchronized SkillDefinition select(String id,String version){String selected=version==null?defaults.get(id):version;var found=view(id,selected);if(!found.enabled())throw new IllegalStateException("SKILL_VERSION_DISABLED");return found.definition();}
    public synchronized void setDefault(String id,String version){select(id,version);defaults.put(id,version);}
    /** Disabling rejects new runs; already pinned runs continue under live policy checks. */
    public synchronized void setEnabled(String id,String version,boolean value){var view=view(id,version);if(value&&!"ACTIVE".equals(view.definition().state()))throw new IllegalStateException("SKILL_DEFINITION_DISABLED");enabled.put(new Key(id,version),value);}
}
