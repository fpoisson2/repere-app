package ca.repere.data

import android.content.Context

class ApiOperationRepository(context:Context) {
    private val dao=RepereDatabase.get(context).dao()
    suspend fun enqueue(operation:PendingApiOperation)=dao.putApiOperation(operation)
    suspend fun pending():List<PendingApiOperation> = dao.pendingApiOperations()
    suspend fun complete(id:String)=dao.deleteApiOperation(id)
    suspend fun failed(operation:PendingApiOperation,error:String?)=dao.putApiOperation(operation.copy(attempts=operation.attempts+1,lastError=error))
}
