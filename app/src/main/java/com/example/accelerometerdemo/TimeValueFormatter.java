package com.example.accelerometerdemo;

import com.github.mikephil.charting.formatter.ValueFormatter;

public class TimeValueFormatter extends ValueFormatter {
    @Override
    public String getFormattedValue(float value) {
        // You can customize the formatting here if needed
        return (int) value + "s";
    }
}
