import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;

public class Test {
    public static void main(String[] args) throws Exception {
        URLClassLoader loader = new URLClassLoader(new URL[]{new java.io.File("/opt/android/sdk/platforms/android-36/android.jar").toURI().toURL()}, null);
        Class<?> clazz = Class.forName("android.media.AudioDeviceInfo", true, loader);
        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getName().startsWith("TYPE_")) {
                field.setAccessible(true);
                System.out.println(field.getName() + " = " + field.get(null));
            }
        }
    }
}
