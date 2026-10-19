package com.example.mimic.downloader.data.local.db

import androidx.room.TypeConverter
import com.example.mimic.downloader.data.model.DownloadCategory
import com.example.mimic.downloader.data.model.DownloadStatus
import com.example.mimic.downloader.data.model.FormatKind

class Converters {
    @TypeConverter
    fun fromDownloadStatus(value: DownloadStatus): String = value.name

    @TypeConverter
    fun toDownloadStatus(value: String): DownloadStatus = DownloadStatus.valueOf(value)

    @TypeConverter
    fun fromDownloadCategory(value: DownloadCategory): String = value.name

    @TypeConverter
    fun toDownloadCategory(value: String): DownloadCategory = DownloadCategory.valueOf(value)

    @TypeConverter
    fun fromFormatKind(value: FormatKind): String = value.name

    @TypeConverter
    fun toFormatKind(value: String): FormatKind = FormatKind.valueOf(value)
}
