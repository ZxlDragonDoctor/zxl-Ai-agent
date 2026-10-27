package com.zxl.zxlaiagent.security;
public record ResourceRef(String type,String id) { public static ResourceRef tenant(){return new ResourceRef("tenant", "current");} }
