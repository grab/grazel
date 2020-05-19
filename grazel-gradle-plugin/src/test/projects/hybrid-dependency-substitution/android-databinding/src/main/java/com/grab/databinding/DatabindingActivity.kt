package com.grab.databinding

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.BaseObservable
import androidx.databinding.BindingAdapter
import androidx.databinding.DataBindingUtil
import androidx.databinding.ObservableField
import com.grab.databinding.databinding.ActivityMainBinding

data class ViewModel(
    var counter: ObservableField<String> = ObservableField<String>("Play")
) : BaseObservable() {
    fun increment() = counter.set("Up")
    fun decrement() = counter.set("Down")
}

@BindingAdapter("viewModel")
fun TextView.viewModel(viewModel: ViewModel) {
    text = "Counter : ${viewModel.counter.get()}"
}

class DatabindingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = DataBindingUtil.setContentView<ActivityMainBinding>(
            this,
            R.layout.activity_main
        )
        binding.vm = ViewModel()
    }
}