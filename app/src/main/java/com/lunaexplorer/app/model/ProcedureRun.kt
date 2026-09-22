package com.lunaexplorer.app.model

data class ProcedureRun(
    val id: String,
    val procedureId: String,
    val title: String,
    val status: String,
    val detail: String,
    val created: Long,
)
