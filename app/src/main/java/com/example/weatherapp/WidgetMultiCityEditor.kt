package com.example.weatherapp

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat

/** A local draft: changing any slot cannot configure or close the widget. */
internal class WidgetMultiCityEditor(c: Context,private val widgetId: Int,restored: Bundle?,private val complete: ()->Unit): LinearLayout(c) {
    private data class Option(val label: String,val city: CityPage?,val none: Boolean=false,val follow: Boolean=false)
    private val slots=MutableList<CityPage?>(3){null}
    private var follows=false
    private var thirdChosen=false
    private val spinners=ArrayList<Spinner>()
    private val status=TextView(c)
    private val done=Button(c)
    init {
        orientation=VERTICAL
        if(WeatherWidgetStore.isConfigured(c,widgetId)) {
            WeatherWidgetStore.cities(c,widgetId).forEachIndexed{i,city->slots[i]=city}
            follows=WeatherWidgetStore.followsLocation(c,widgetId);thirdChosen=true
        }
        if(restored?.getBoolean("multi_draft")==true) {
            repeat(3){i->slots[i]=if(restored.getBoolean("multi_has_$i")) CityPage(restored.getString("multi_name_$i").orEmpty(),restored.getDouble("multi_lng_$i"),restored.getDouble("multi_lat_$i"),false) else null}
            follows=restored.getBoolean("multi_follow");thirdChosen=restored.getBoolean("multi_third_chosen")
        }
        val group=WidgetSettingsGroup(c,"widget_multi_city_group")
        addView(group,LayoutParams(-1,-2).apply{topMargin=dp(14)})
        repeat(3){i->
            val row=LinearLayout(c).apply{orientation=HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(10),dp(16),dp(10));minimumHeight=dp(64)}
            row.addView(LinearLayout(c).apply{orientation=VERTICAL
                addView(TextView(c).apply{text="第${i+1}城";textSize=15f;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(ContextCompat.getColor(c,R.color.text_primary))})
                addView(TextView(c).apply{text=if(i<2)"必选" else "可不显示";textSize=12f;setTextColor(ContextCompat.getColor(c,R.color.text_secondary))})
            },LayoutParams(dp(70),-2))
            val spinner=WidgetCitySpinner(c).apply{tag="widget_multi_slot_$i";minimumHeight=dp(48);contentDescription="选择第${i+1}城";background=null}
            spinners+=spinner;row.addView(spinner,LayoutParams(0,-2,1f))
            row.addView(TextView(c).apply{text="⌄";textSize=20f;setTextColor(ContextCompat.getColor(c,R.color.text_secondary));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LayoutParams(dp(20),-2))
            row.isClickable=true;row.isFocusable=true;WidgetSettingsGroup.ripple(row);row.setOnClickListener{spinner.performClick()};group.row(row)
        }
        status.textSize=13f;status.setTextColor(ContextCompat.getColor(c,R.color.text_primary));status.tag="widget_multi_status"
        status.setPadding(dp(16),dp(12),dp(16),dp(12));group.row(status)
        done.text="完成";done.tag="widget_multi_done"
        done.minimumHeight=dp(52);done.elevation=0f;done.stateListAnimator=null;WidgetSettingsGroup.ripple(done)
        done.setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled),intArrayOf()),intArrayOf(ContextCompat.getColor(c,R.color.text_primary),ContextCompat.getColor(c,R.color.text_secondary))))
        group.row(done)
        done.setOnClickListener {
            if(valid() && WeatherWidgetStore.setCities(c,widgetId,slots[0]!!,slots.drop(1).filterNotNull(),follows)) complete()
        }
        refresh()
    }
    private fun dp(v: Int)=(v*resources.displayMetrics.density).toInt()
    private fun valid(): Boolean {
        val selected=slots.filterNotNull()
        return slots[0]!=null && slots[1]!=null && thirdChosen && selected.all(WeatherWidgetPolicy::validCity) && selected.indices.none{i->selected.take(i).any{WeatherWidgetPolicy.sameCity(it,selected[i])}}
    }
    private fun refresh() {
        val location=WeatherCityStore.currentLocationCity(context)?.takeIf(WeatherWidgetPolicy::validCity)
        repeat(3){slot->
            val choices=ArrayList<Option>();choices+=Option("请选择城市",null)
            if(slot==2) choices+=Option("无城市 · 显示两城",null,none=true)
            val other=slots.filterIndexed{i,_->i!=slot}.filterNotNull()
            if(location!=null && other.none{WeatherWidgetPolicy.sameCity(it,location)}) choices+=Option(if(slot==0) "跟随当前位置 · ${WeatherAddressFormatter.city(location.name)}" else WeatherAddressFormatter.city(location.name),location,follow=slot==0)
            WeatherCityStore.cities(context).filter{WeatherWidgetPolicy.validCity(it) && !it.isCurrentLocation}.forEach {city->
                if(other.none{WeatherWidgetPolicy.sameCity(it,city)} && choices.none{it.city?.let{v->WeatherWidgetPolicy.sameCity(v,city)}==true}) choices+=Option(WeatherAddressFormatter.city(city.name),city)
            }
            slots[slot]?.let{city->if(choices.none{it.city?.let{v->WeatherWidgetPolicy.sameCity(v,city)}==true}) choices+=Option(WeatherAddressFormatter.city(city.name),city,follow=slot==0&&follows)}
            val spinner=spinners[slot];spinner.onItemSelectedListener=null
            spinner.adapter=object:ArrayAdapter<String>(context,android.R.layout.simple_spinner_item,choices.map{it.label}) {
                override fun isEnabled(position: Int)=position>0
                override fun getView(position: Int,convertView: View?,parent: android.view.ViewGroup): View = super.getView(position,convertView,parent).apply{(this as? TextView)?.let{it.textSize=14f;it.setTextColor(ContextCompat.getColor(context,R.color.text_secondary));it.gravity=android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL;it.setPadding(0,dp(8),dp(4),dp(8))}}
            }.apply{setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)}
            val selected=if(slot==2&&thirdChosen&&slots[slot]==null) 1 else choices.indexOfFirst{it.city!=null && slots[slot]?.let{v->WeatherWidgetPolicy.sameCity(it.city,v)}==true}.coerceAtLeast(0)
            spinner.setSelection(selected,false)
            spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) {
                    if(position<=0 || position>=choices.size) return
                    val picked=choices[position]
                    val same=if(picked.none) slots[slot]==null && thirdChosen else slots[slot]?.let{old->picked.city?.let{WeatherWidgetPolicy.sameCity(old,it)}}==true && (slot!=0 || follows==picked.follow)
                    if(same) return
                    slots[slot]=picked.city;if(slot==0) follows=picked.follow;if(slot==2) thirdChosen=true
                    refresh()
                }
            }
        }
        done.isEnabled=valid()
        status.text=if(valid()) "城市已选好，点击“完成”保存" else "请选择第1、2城，并确认第3城或选择无城市。城市不足时，请先在App城市管理添加。"
    }
    fun saveDraft(out: Bundle) {
        out.putBoolean("multi_draft",true);out.putBoolean("multi_follow",follows);out.putBoolean("multi_third_chosen",thirdChosen)
        slots.forEachIndexed{i,city->out.putBoolean("multi_has_$i",city!=null);city?.let{out.putString("multi_name_$i",it.name);out.putDouble("multi_lng_$i",it.lng);out.putDouble("multi_lat_$i",it.lat)}}
    }
}
