package com.example.weatherapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat

/** One settings surface contains any number of editable rows, including future providers. */
internal class WidgetSettingsGroup(c: Context,groupTag: String): LinearLayout(c) {
    init {orientation=VERTICAL;SettingsGlassStyle.decorate(this,14f);tag=groupTag;clipToOutline=true}
    fun row(view: View) {
        if(childCount>0) addView(View(context).apply{setBackgroundColor(ContextCompat.getColor(context,R.color.divider))},LayoutParams(-1,dp(1)).apply{leftMargin=dp(16);rightMargin=dp(16)})
        addView(view,LayoutParams(-1,-2))
    }
    fun action(label: String,rowTag: String?,click: ()->Unit): TextView {
        val v=TextView(context).apply{text=label;textSize=15f;setTypeface(null,Typeface.BOLD);setTextColor(ContextCompat.getColor(context,R.color.text_primary));setPadding(dp(16),dp(16),dp(16),dp(16));minHeight=dp(56);gravity=Gravity.CENTER_VERTICAL;isClickable=true;isFocusable=true;tag=rowTag;setOnClickListener{click()};ripple(this)}
        row(v);return v
    }
    private fun dp(v: Int)=(v*resources.displayMetrics.density).toInt()
    companion object {
        fun ripple(view: View) {val a=view.context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground));view.background=a.getDrawable(0);a.recycle()}
    }
}

internal class WidgetCitySpinner(c: Context) : FrostedSelectionSpinner(c, firstItem = 1)

/** Anchored frosted menu for both complete lists and selectors with a non-selectable first entry. */
internal open class FrostedSelectionSpinner(c: Context, private val firstItem: Int = 0) : Spinner(c) {
    private val popupTag = if (firstItem == 1) "widget_city_popup" else "settings_choice_popup"
    internal var openPopup: PopupWindow?=null
        private set
    override fun performClick(): Boolean {
        val currentAdapter=adapter ?: return false
        if (currentAdapter.count <= firstItem) return false
        val d=resources.displayMetrics.density
        fun dp(v: Int)=(v*d).toInt()
        val container=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;clipToOutline=true;tag=popupTag}
        val list=ListView(context).apply{divider=ColorDrawable(ContextCompat.getColor(context,R.color.divider));dividerHeight=dp(1);setPadding(dp(12),dp(4),dp(12),dp(4));isVerticalScrollBarEnabled=false}
        list.adapter=object:BaseAdapter(){
            override fun getCount()=currentAdapter.count - firstItem
            override fun getItem(position: Int)=currentAdapter.getItem(position + firstItem)
            override fun getItemId(position: Int)=(position + firstItem).toLong()
            override fun getView(position: Int,convert: View?,parent: ViewGroup): View {
                return LinearLayout(context).apply{
                    gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(52);setPadding(dp(4),dp(10),dp(4),dp(10))
                    addView(TextView(context).apply{text=getItem(position).toString();textSize=14f;setTextColor(ContextCompat.getColor(context,R.color.text_primary));if(selectedItemPosition == position + firstItem)setTypeface(null,Typeface.BOLD)},LinearLayout.LayoutParams(0,-2,1f))
                    addView(ImageView(context).apply{setImageResource(R.drawable.ic_check);setColorFilter(ContextCompat.getColor(context,R.color.accent));visibility=if(selectedItemPosition == position + firstItem)View.VISIBLE else View.INVISIBLE},LinearLayout.LayoutParams(dp(18),dp(18)).apply{leftMargin=dp(12)})
                }
            }
        }
        container.addView(list,LinearLayout.LayoutParams(-1,-1))
        SettingsGlassStyle.decoratePopup(container,rootView);container.tag=popupTag
        val width=dp(268).coerceAtMost(resources.displayMetrics.widthPixels-dp(32))
        val height=minOf((currentAdapter.count - firstItem)*dp(52)+dp(8),(resources.displayMetrics.heightPixels*.5f).toInt())
        val popup=PopupWindow(container,width,height,true).apply{setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));elevation=dp(10).toFloat();isOutsideTouchable=false}
        openPopup?.dismiss();openPopup=popup
        list.setOnItemClickListener{_,_,position,_->setSelection(position + firstItem);popup.dismiss()}
        popup.setOnDismissListener{if(openPopup===popup)openPopup=null}
        popup.showAsDropDown(this,this.width-width,-dp(6));postDelayed({if(openPopup===popup)popup.isOutsideTouchable=true},120)
        return true
    }
    override fun onDetachedFromWindow(){openPopup?.dismiss();super.onDetachedFromWindow()}
}
