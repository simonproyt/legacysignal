package java.beans

open class FeatureDescriptor {
    fun getValue(attributeName: String): Any? = null
}

class IntrospectionException(message: String) : Exception(message)

class Introspector {
    companion object {
        @JvmStatic
        fun getBeanInfo(beanClass: Class<*>): BeanInfo? = null
    }
}

interface BeanInfo {
    fun getPropertyDescriptors(): Array<PropertyDescriptor>
}

class PropertyDescriptor : FeatureDescriptor() {
    fun getReadMethod(): java.lang.reflect.Method? = null
    fun getWriteMethod(): java.lang.reflect.Method? = null
    fun getPropertyType(): Class<*>? = null
}
