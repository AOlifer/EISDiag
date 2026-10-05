# Правила R8 для release.
# Свой код через reflection не вызывается: reflection идёт только к классам прошивки
# (bw.car.*, android.os.SystemProperties), которых в APK нет. Activity, Service и Receiver
# из манифеста AGP сохраняет сам.

# Номера строк в трассировках падений.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
