package com.example.weatherapp

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class ApiCheckState(val running: Boolean = false, val requests: Int = 0,
                         val progress: String = "", val report: ApiCapabilityReport? = null,
                         val stopped: Boolean = false, val failed: Boolean = false,
                         val savedConfiguration: Boolean = true)

/** Keeps an in-flight check across rotation without putting credentials into saved state. */
class ApiCapabilityViewModel(private val transport: ApiProbeTransport? = null) : ViewModel() {
    val state = MutableLiveData(ApiCheckState())
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var future: Future<*>? = null
    private var active: ApiCapabilityProbe? = null
    private var lastConfiguration: UserApiConfiguration.Config? = null
    fun editingConfiguration(): UserApiConfiguration.Config? = lastConfiguration?.takeIf {state.value?.savedConfiguration==false}
    fun retry() {lastConfiguration?.let {start(it,state.value?.savedConfiguration ?: true)}}
    fun start(config: UserApiConfiguration.Config,savedConfiguration: Boolean = true) {
        if(active != null) return
        lastConfiguration=config
        val probe=ApiCapabilityProbe(transport); active=probe
        state.value=ApiCheckState(running=true,progress="正在准备检测（最多约1分钟）",savedConfiguration=savedConfiguration)
        future=worker.submit {
            try {
                val result=probe.run(config) { count,label -> main.post {
                    if(active === probe) state.value=ApiCheckState(true,count,label,savedConfiguration=savedConfiguration)
                } }
                main.post { if(active === probe) { active=null; future=null; state.value=ApiCheckState(report=result,savedConfiguration=savedConfiguration) } }
            } catch (_: Exception) {
                main.post { if(active === probe) { active=null; future=null; state.value=ApiCheckState(failed=true,savedConfiguration=savedConfiguration) } }
            }
        }
    }
    fun dismissReport() { if(active == null) state.value=ApiCheckState() }
    fun cancel() {
        val saved=state.value?.savedConfiguration ?: true
        stop(); state.value=ApiCheckState(stopped=true,savedConfiguration=saved)
    }
    private fun stop() { val old=active;active=null;old?.cancel();future?.cancel(true);future=null }
    override fun onCleared() { stop(); worker.shutdownNow() }
}
