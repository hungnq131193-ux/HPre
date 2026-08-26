# FlowTube ProGuard / R8 Rules
# Keep rules are added strictly on observed need during minification.

# FlowTube Application & Components entrypoints
-keep class com.flowtube.app.FlowTubeApplication {
    public <init>();
}
-keep class com.flowtube.app.MainActivity {
    public <init>();
}

# NewPipe Extractor transitive dependency org.mozilla.javascript (Rhino) references optional desktop Java runtime classes
# not present in Android runtime. R8 failure output when omitted:
# "Missing class java.beans.BeanDescriptor (referenced from: java.lang.Object org.mozilla.javascript.JavaToJSONConverters.lambda$static$4(java.lang.Object))"
# "Missing class java.beans.BeanInfo (referenced from: java.lang.Object org.mozilla.javascript.JavaToJSONConverters.lambda$static$4(java.lang.Object))"
# "Missing class java.beans.IntrospectionException (referenced from: java.lang.Object org.mozilla.javascript.JavaToJSONConverters.lambda$static$4(java.lang.Object))"
# "Missing class java.beans.Introspector (referenced from: java.lang.Object org.mozilla.javascript.JavaToJSONConverters.lambda$static$4(java.lang.Object))"
# "Missing class java.beans.PropertyDescriptor (referenced from: java.lang.Object org.mozilla.javascript.JavaToJSONConverters.lambda$static$4(java.lang.Object))"
# "Missing class javax.script.ScriptEngineFactory (referenced from: org.mozilla.javascript.engine.RhinoScriptEngineFactory)"
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn javax.script.ScriptEngineFactory

