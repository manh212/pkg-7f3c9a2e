-keep class com.google.mediapipe.** { *; }
-keep class com.google.common.flogger.** { *; }
-keepclassmembers class com.google.mediapipe.** extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate
