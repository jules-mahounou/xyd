# --- xyd : règles R8 orientées taille ---

# Pas de stacktrace lisible nécessaire en prod : on laisse R8 tout renommer.
-repackageclasses ''
-allowaccessmodification

# Supprime les vérifications de nullité Kotlin générées sur les paramètres.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkParameterIsNotNull(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void checkFieldIsNotNull(...);
}

# Supprime les logs de debug.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
-dontwarn javax.annotation.**
