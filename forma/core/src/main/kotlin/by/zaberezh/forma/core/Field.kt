package by.zaberezh.forma.core

/** Описание числового поля — по нему UI строит форму (замеры, профиль, будущие сферы). */
data class Field(val key: String, val label: String, val unit: String = "", val hint: String = "")
