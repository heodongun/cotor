def fibonacci(n: int) -> int:
    """
    Calculate the nth fibonacci number efficiently using memoization.

    Args:
        n: The position in the fibonacci sequence (0-indexed)

    Returns:
        The nth fibonacci number

    Raises:
        ValueError: If n is negative
    """
    if n < 0:
        raise ValueError("n must be non-negative")

    if n <= 1:
        return n

    # Use iterative approach with O(n) time and O(1) space
    prev, curr = 0, 1
    for _ in range(2, n + 1):
        prev, curr = curr, prev + curr

    return curr


def fibonacci_memoized(n: int, memo: dict = None) -> int:
    """
    Calculate the nth fibonacci number using memoization (top-down dynamic programming).

    Args:
        n: The position in the fibonacci sequence (0-indexed)
        memo: Dictionary to store previously calculated values

    Returns:
        The nth fibonacci number

    Raises:
        ValueError: If n is negative
    """
    if n < 0:
        raise ValueError("n must be non-negative")

    if memo is None:
        memo = {}

    if n in memo:
        return memo[n]

    if n <= 1:
        return n

    memo[n] = fibonacci_memoized(n - 1, memo) + fibonacci_memoized(n - 2, memo)
    return memo[n]


# Example usage
if __name__ == "__main__":
    # Test both implementations
    test_values = [0, 1, 5, 10, 20, 30]

    print("Iterative approach:")
    for n in test_values:
        print(f"fibonacci({n}) = {fibonacci(n)}")

    print("\nMemoized approach:")
    for n in test_values:
        print(f"fibonacci_memoized({n}) = {fibonacci_memoized(n)}")
