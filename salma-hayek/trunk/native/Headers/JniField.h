#ifndef JNI_FIELD_H_included
#define JNI_FIELD_H_included

#include <jni.h>
#include <sstream>
#include <stdexcept>
#include <string>

/**
 * Hides the details of accessing Java fields via JNI.
 */
template <typename NativeT>
class JniField {
    typedef JniField<NativeT> Self;
    
    JNIEnv* m_env;
    jobject m_instance;
    const char* m_fieldName;
    const char* m_fieldSignature;
    
public:
    // Creates a proxy for an instance field.
    // FIXME: should be able to proxy static fields.
    JniField(JNIEnv* env, jobject instance, const char* name, const char* signature)
    : m_env(env)
    , m_instance(instance)
    , m_fieldName(name)
    , m_fieldSignature(signature)
    {
    }
    
    Self& operator=(const NativeT& rhs) {
        set(rhs);
        return *this;
    }
    
    NativeT get() const {
        NativeT result;
        get(result);
        return result;
    }
    
private:
    void get(NativeT&) const;
    void set(const NativeT&);
    
    jfieldID getFieldID() const {
        // It's not a valid optimization to cache field ids in face of class
        // unloading. We could keep a global reference to the class to prevent
        // it being unloaded, but that seems unfriendly.
        
        // The JNI specification suggests that GetObjectClass can't fail.
        // http://java.sun.com/j2se/1.5.0/docs/guide/jni/spec/functions.html
        jclass objectClass = m_env->GetObjectClass(m_instance);
        jfieldID result = m_env->GetFieldID(objectClass, m_fieldName, m_fieldSignature);
        if (result == 0) {
            std::ostringstream message;
            message << "couldn't find field " << m_fieldName << " (" << m_fieldSignature << ")";
            throw std::runtime_error(message.str());
        }
        return result;
    }
};

#define org_jessies_JniField_ACCESSORS(TYPE, FUNCTION_NAME_FRAGMENT) \
    template <> void JniField<TYPE>::set(const TYPE& rhs) { m_env->Set ## FUNCTION_NAME_FRAGMENT ## Field(m_instance, getFieldID(), rhs); } \
    template <> void JniField<TYPE>::get(TYPE& result) const { result = (TYPE) m_env->Get ## FUNCTION_NAME_FRAGMENT ## Field(m_instance, getFieldID()); }

org_jessies_JniField_ACCESSORS(jstring, Object)
org_jessies_JniField_ACCESSORS(jobject, Object)
org_jessies_JniField_ACCESSORS(jboolean, Boolean)
org_jessies_JniField_ACCESSORS(jbyte, Byte)
org_jessies_JniField_ACCESSORS(jchar, Char)
org_jessies_JniField_ACCESSORS(jshort, Short)
org_jessies_JniField_ACCESSORS(jint, Int)
org_jessies_JniField_ACCESSORS(jlong, Long)
org_jessies_JniField_ACCESSORS(jfloat, Float)
org_jessies_JniField_ACCESSORS(jdouble, Double)

#undef org_jessies_JniField_ACCESSORS

#endif
