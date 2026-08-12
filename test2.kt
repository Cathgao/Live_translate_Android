import android.media.AudioDeviceInfo
import java.lang.reflect.Modifier

fun main() {
    for (field in AudioDeviceInfo::class.java.declaredFields) {
        if (Modifier.isStatic(field.modifiers) && field.name.startsWith("TYPE_")) {
            val value = field.get(null)
            if (value == 25) {
                println(field.name)
            }
        }
    }
}
