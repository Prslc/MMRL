package com.dergoogler.mmrl.platform

import android.app.ActivityThread
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.ServiceManager
import android.util.Log
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dergoogler.mmrl.platform.content.IService
import com.dergoogler.mmrl.platform.content.Service
import com.dergoogler.mmrl.platform.hiddenApi.HiddenPackageManager
import com.dergoogler.mmrl.platform.hiddenApi.HiddenUserManager
import com.dergoogler.mmrl.platform.model.PlatformConfig
import com.dergoogler.mmrl.platform.model.PlatformConfigImpl
import com.dergoogler.mmrl.platform.stub.IFileManager
import com.dergoogler.mmrl.platform.stub.IModuleManager
import com.dergoogler.mmrl.platform.stub.IServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.FileDescriptor

const val TIMEOUT_MILLIS = 15_000L
const val PLATFORM_KEY = "PLATFORM"
internal const val BINDER_TRANSACTION = 84398154

/**
 * Represents the various platforms supported by the application.
 *
 * @property id A unique identifier for the platform.
 */
enum class Platform(val id: String) {
    Magisk("magisk"),
    KernelSU("kernelsu"),
    KsuNext("ksunext"),
    APatch("apatch"),
    MKSU("mksu"),
    SukiSU("sukisu"),
    RKSU("rksu"),
    NonRoot("");

    companion object {
        private const val TAG = "Platform"
        var mServiceOrNull: IServiceManager? = null
        val mService
            get() = checkNotNull(mServiceOrNull) {
                "IServiceManager haven't been received"
            }

        var isAlive by mutableStateOf(false)
            private set

        private val _isAliveFlow = MutableStateFlow(false)
        val isAliveFlow get() = _isAliveFlow.asStateFlow()

        /**
         * Initializes the platform with the given configuration.
         *
         * This function sets up the underlying service manager for the platform. It requires
         * a valid `android.content.Context` and a supported `com.dergoogler.mmrl.platform.Platform`
         * to function correctly. If the platform is already initialized, it simply returns `true`.
         *
         * @param config A lambda function with a receiver of type `PlatformConfig` that allows
         *               configuring the platform's settings (e.g., context, platform type, debug mode).
         *               This lambda is executed within the scope of a `PlatformConfig` instance,
         *               allowing direct access to its properties and functions.
         * @return `true` if the platform was successfully initialized or was already initialized,
         *         `false` if the initialization failed due to missing configuration or an error.
         *
         * @throws Exception If any unexpected error occurs during the initialization process.
         *
         * **Error Handling:**
         * - If the provided configuration doesn't include a `context`, it logs an error and returns `false`.
         * - If the configuration lacks a specified `platform` type, it also logs an error and returns `false`.
         * - If an exception occurs during service manager creation, it catches the exception, logs it, clears the service, and returns the current `state()`
         *
         * **Initialization Process:**
         * 1. Applies the provided configuration lambda to a `PlatformConfigImpl` instance.
         * 2. Checks if the context and platform are defined in the configuration.
         * 3. If the platform is already alive (`isAlive` is true), returns `true` immediately.
         * 4. Otherwise, attempts to create the service manager based on the specified platform.
         * 5. Uses `ServiceManagerCompat.fromLibSu` for Magisk, KsuNext, KernelSU, and APatch platforms, if no `fromProvider` is specified.
         * 6. If no `fromProvider` and a different platform, it sets the service to null.
         * 7. Calls the `state()` function to get the initialized or current state.
         * 8. Returns true if no exception occurs and state() is successful
         * 9. On exception clears the service to null and returns the state()
         */
        suspend fun init(config: suspend PlatformConfig.() -> Unit): Boolean {
            val conf = PlatformConfigImpl().applyConfig(config)

            if (conf.context == null) {
                throw IllegalArgumentException("Context cannot be null")
            }

            if (conf.platform == null) {
                throw IllegalArgumentException("Platform cannot be null")
            }

            if (conf.provider == null) {
                throw IllegalArgumentException("Provider cannot be null")
            }

            if (conf.platform == NonRoot) {
                Log.e(TAG, "Platform.init(...) cannot run as non-root!")
                return false
            }

            return when {
                isAlive -> true
                else -> try {
                    mServiceOrNull = when (conf.platform) {
                        Magisk,
                        KsuNext,
                        KernelSU,
                        SukiSU,
                        RKSU,
                        MKSU,
                        APatch,
                            -> conf.provider

                        else -> null
                    }

                    state()
                } catch (e: Exception) {
                    mServiceOrNull = null
                    Log.e(TAG, "Failed to init service manager", e)
                    state()
                }
            }
        }

        private suspend fun PlatformConfigImpl.applyConfig(block: suspend PlatformConfig.() -> Unit): PlatformConfigImpl {
            block()
            return this
        }

        /**
         * Provides access to the module manager, which is responsible for managing the available modules.
         *
         * The module manager allows you to:
         * - Get a list of all available modules.
         * - Find a specific module by its identifier.
         * - Check if a module is enabled or disabled.
         * - (Potentially) Enable or disable modules, depending on the underlying implementation.
         * - (Potentially) Interact with individual modules.
         *
         * This property delegates to the module manager held by the underlying service [mService].
         *
         * @see IModuleManager
         */
        val moduleManager: IModuleManager get() = mService.moduleManager

        /**
         * Provides access to the file management service.
         *
         * This property allows interaction with the underlying file management system, enabling
         * operations like creating, deleting, reading, and writing files and directories.
         *
         * @see IFileManager
         */
        val fileManager: IFileManager get() = mService.fileManager
        internal val fileManagerOrNull: IFileManager? get() = mServiceOrNull?.fileManager

        /**
         *  Provides access to package management related information and operations.
         *
         *  This property allows interaction with the system's package manager,
         *  allowing for querying and manipulating installed applications,
         *  their components, permissions, and other package-related data.
         *
         *  @see HiddenPackageManager
         */
        val packageManager get(): HiddenPackageManager = HiddenPackageManager(this.mService)

        /**
         * Provides access to hidden user management functionality.
         *
         * This property returns an instance of [HiddenUserManager], which allows you to interact
         * with user management features that are typically hidden from regular app use.
         * These features may include creating, deleting, or managing users beyond the standard
         * Android user profiles.
         *
         * **Warning:** Accessing and modifying hidden users can have significant impacts on the
         * device's stability and security. Exercise extreme caution when using this feature,
         * and ensure you fully understand the potential consequences of any actions taken
         * through the [HiddenUserManager].
         *
         * @see HiddenUserManager
         */
        val userManager get(): HiddenUserManager = HiddenUserManager(this.mService)

        /**
         * The SELinux context associated with the binder service.
         *
         * This property provides the SELinux security context string that the binder service is running under.
         * This context is used by the kernel's SELinux module to enforce security policies and control access to resources.
         *
         * @see [android.os.Binder.getCallingPid] For more information about how process SELinux contexts are managed.
         * @see [android.os.Binder.getCallingUid] For more information about how user SELinux contexts are managed.
         * @see [android.os.Binder] For more information about the Binder IPC mechanism.
         *
         * @return A String representing the SELinux context of the binder service.
         */
        val seLinuxContext: String get() = mService.seLinuxContext

        /**
         * The currently active platform.
         *
         * This property retrieves the platform information from the underlying service.
         * It uses the `currentPlatform()` method of the service and converts it to a [Platform] enum value.
         *
         * If the service is not available or `currentPlatform()` returns an invalid or unknown value,
         * this property will return `Platform.NonRoot`.
         *
         * The retrieval is done using `serviceOrNull`, ensuring a safe access to the service.
         *
         * @see Platform
         */
        val platform: Platform
            get() = serviceOrNull(NonRoot) {
                Platform.from(currentPlatform())
            }

        /**
         * Checks the current state of the platform service.
         *
         * This function updates the `isAlive` property and the `_isAliveFlow` based on whether
         * the internal service manager (`mServiceOrNull`) is currently available (not null).
         * It operates within the `Dispatchers.IO` context to avoid blocking the main thread.
         *
         * @return `true` if the service is alive (i.e., `mServiceOrNull` is not null), `false` otherwise.
         */
        suspend fun state(): Boolean = withContext(Dispatchers.IO) {
            isAlive = mServiceOrNull != null
            _isAliveFlow.value = isAlive
            isAlive
        }

        /**
         * Retrieves a value from a block if the object is alive, otherwise returns a fallback value.
         *
         * This function provides a safe way to access properties or perform actions within a block
         * only when the object associated with this companion object is considered "alive".
         * If the object is not alive, the function gracefully returns a pre-defined fallback value.
         *
         * @param fallback The value to return if the object is not alive.
         * @param block A lambda function that takes the Companion object as its receiver and returns a value of type T.
         *              This block is executed only if `isAlive` is true.
         * @return The value returned by the block if the object is alive, otherwise the fallback value.
         * @param T The type of the value being retrieved.
         * @throws Any exceptions that the provided `block` may throw.
         */
        fun <T> get(fallback: T, block: Companion.() -> T): T {
            return when {
                isAlive -> block(this)
                else -> fallback
            }
        }

        /**
         * Asynchronously retrieves a value from a block if the object is alive, otherwise returns a fallback value.
         *
         * This function provides a safe way to asynchronously access properties or perform suspendable actions
         * within a block only when the object associated with this companion object is considered "alive".
         * If the object is not alive, the function gracefully returns a [Deferred] containing the pre-defined fallback value.
         *
         * The operation is launched within the provided [CoroutineScope].
         *
         * @param scope The [CoroutineScope] in which to launch the asynchronous operation.
         * @param fallback The value to return in the [Deferred] if the object is not alive.
         * @param block A suspendable lambda function that takes the Companion object as its receiver and returns a value of type T.
         *              This block is executed only if `isAlive` is true.
         * @return A [Deferred] that will eventually hold the value returned by the block if the object is alive,
         *         otherwise it will hold the fallback value.
         * @param T The type of the value being retrieved.
         * @throws Any exceptions that the provided `block` may throw during its execution.
         */
        inline fun <T> getAsyncDeferred(
            scope: CoroutineScope,
            fallback: T,
            crossinline block: @DisallowComposableCalls suspend Companion.() -> T,
        ): Deferred<T> {
            return scope.async {
                if (isAlive) block(this@Companion) else fallback
            }
        }

        /**
         * Sets hidden API exemptions for the current process.
         *
         * This function allows you to bypass the hidden API restrictions introduced in Android P (API level 28) and later.
         * It does so by adding signature prefixes to the list of exemptions.
         *
         * Note that this function is only effective on Android P and above. On earlier versions, it does nothing and returns true.
         *
         * **Important Security Considerations:**
         *
         * - Using hidden APIs is strongly discouraged by Google and can lead to app instability and crashes.
         * - Exemption from these restrictions can introduce security vulnerabilities if not handled with extreme care.
         * - This functionality might break without notice in future Android versions.
         * - Use this function with caution, and only when absolutely necessary, after thoroughly understanding the risks.
         * - This is useful when working with legacy code that uses hidden apis, or for research purposes.
         *
         * @param signaturePrefixes A vararg of String representing the signature prefixes to be added to the exemption list.
         *                          If no prefixes are provided, it defaults to an empty string, which effectively bypasses all hidden api checks
         *                          (not recommended for production). Each string in the vararg must be a valid signature prefix.
         *
         * @return `true` if the operation succeeded or if the SDK version is lower than P (in which case no action is taken).
         *         It returns `false` if the addHiddenApiExemptions returns false in the HiddenApiBypass class.
         *         Please note that the `addHiddenApiExemptions` method in the `HiddenApiBypass` class might return `false` in certain scenarios, even if the method runs without error.
         *         Such cases are related to the internal workings of the `HiddenApiBypass` class, and may depend on the device or android version.
         *
         * @see HiddenApiBypass.addHiddenApiExemptions
         */
        fun setHiddenApiExemptions(vararg signaturePrefixes: String = arrayOf("")) = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> HiddenApiBypass.addHiddenApiExemptions(
                *signaturePrefixes
            )

            else -> true
        }

        fun from(value: String): Platform {
            return entries.firstOrNull { it.id == value } ?: NonRoot
        }

        fun <T : IBinder> T.proxyBy(service: IServiceManager) = object : IBinder {
            override fun getInterfaceDescriptor() = this@proxyBy.interfaceDescriptor

            override fun pingBinder() = this@proxyBy.pingBinder()

            override fun isBinderAlive() = this@proxyBy.isBinderAlive

            override fun queryLocalInterface(descriptor: String) = null

            override fun dump(fd: FileDescriptor, args: Array<out String>?) =
                this@proxyBy.dump(fd, args)

            override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) =
                this@proxyBy.dumpAsync(fd, args)

            override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) =
                this@proxyBy.linkToDeath(recipient, flags)

            override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int) =
                this@proxyBy.unlinkToDeath(recipient, flags)

            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                val serviceBinder = service.asBinder()
                val newData = Parcel.obtain()

                try {
                    newData.apply {
                        writeInterfaceToken(IServiceManager.DESCRIPTOR)
                        writeStrongBinder(this@proxyBy)
                        writeInt(code)
                        writeInt(flags)
                        appendFrom(data, 0, data.dataSize())
                    }

                    serviceBinder.transact(BINDER_TRANSACTION, newData, reply, 0)
                } finally {
                    newData.recycle()
                }

                return true
            }
        }

        fun <T : IService> addService(clazz: Class<T>): IBinder? = serviceOrNull {
            addService(Service(clazz))
        }

        fun <T : IService> addService(service: Service<T>): IBinder? = serviceOrNull {
            addService(service)
        }

        fun <T : IService> Class<T>.addAsService(): IBinder? = serviceOrNull {
            addService(Service(this@addAsService))
        }

        fun getService(name: String): IBinder? = serviceOrNull {
            getService(name)
        }

        fun <T : IInterface> T.proxyBy(service: IServiceManager) =
            asBinder().proxyBy(service)

        fun <T : IServiceManager> T.getSystemService(name: String) =
            ServiceManager.getService(name).proxyBy(this)

        fun <T> serviceOrNull(default: T, block: IServiceManager.() -> T): T =
            if (mServiceOrNull != null) {
                block(mServiceOrNull!!)
            } else {
                default
            }

        fun <T> serviceOrNull(block: IServiceManager.() -> T): T? = if (mServiceOrNull != null) {
            block(mServiceOrNull!!)
        } else {
            null
        }

        /**
         * Provides the base application [Context].
         *
         * This property traverses the chain of [ContextWrapper] objects, if any,
         * to retrieve the underlying application context. It starts with the context
         * obtained from [ActivityThread.currentApplication()] and unwraps any
         * [ContextWrapper] instances until it reaches the base context, which is
         * typically the application context.
         *
         * This is particularly useful when you need the actual application context
         * and not a wrapped context that might have limited capabilities or
         * lifecycles. For example, when dealing with services or other components
         * that need a context that outlives any specific activity.
         *
         * @return The base application [Context].
         * @throws IllegalStateException if [ActivityThread.currentApplication] returns null, in other words application is not initialized.
         */
        val context
            @Throws(IllegalStateException::class)
            get(): Context {
                var context: Context = ActivityThread.currentApplication()
                while (context is ContextWrapper) {
                    context = context.baseContext
                }

                return context
            }
    }

    val isMagisk get() = this == Magisk
    val isKernelSU get() = this == KernelSU
    val isKernelSuNext get() = this == KsuNext
    val isAPatch get() = this == APatch

    val isNotMagisk get() = !isMagisk
    val isNotKernelSU get() = this != KernelSU && this != KsuNext
    val isNotKernelSuNext get() = !isKernelSuNext
    val isNotAPatch get() = !isAPatch

    val isValid get() = this != NonRoot
    val isNotValid get() = !isValid
    val isKernelSuOrNext get() = this == KernelSU || this == KsuNext

    val current get() = id
}
