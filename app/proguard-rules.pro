# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Preservar atributos de linha para diagnósticos locais sem exposição externa
-keepattributes SourceFile,LineNumberTable

# Proteger rotas tipadas de navegação e payload de metadados da lixeira (kotlinx.serialization)
-keep,includedescriptorclasses class io.github.km20197.limpadroid.ui.navigation.** { *; }
-keep,includedescriptorclasses class io.github.km20197.limpadroid.domain.model.TrashMetadataPayload** { *; }

