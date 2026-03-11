package qa.fixture

data class OrderItem(
    val sku: String,
    val quantity: Int,
)

data class CreateOrderCommand(
    val requestId: String,
    val customerId: String,
    val items: List<OrderItem>,
)

data class OrderResult(
    val orderId: String,
    val totalQuantity: Int,
    val reused: Boolean,
)

interface OrderRepository {
    fun findByRequestId(requestId: String): OrderResult?
    fun save(command: CreateOrderCommand): OrderResult
}

interface AuditLogger {
    fun recordFailure(requestId: String, reason: String)
}

class UserService(
    private val orderRepository: OrderRepository,
    private val auditLogger: AuditLogger,
) {
    fun createOrder(command: CreateOrderCommand): OrderResult {
        require(command.customerId.isNotBlank()) { "customerId is required" }
        require(command.items.isNotEmpty()) { "items must not be empty" }
        require(command.items.all { it.quantity > 0 }) { "quantity must be positive" }

        orderRepository.findByRequestId(command.requestId)?.let {
            return it.copy(reused = true)
        }

        return try {
            val saved = orderRepository.save(command)
            saved.copy(totalQuantity = command.items.sumOf { it.quantity })
        } catch (e: IllegalStateException) {
            auditLogger.recordFailure(command.requestId, e.message ?: "unknown")
            throw e
        }
    }
}
