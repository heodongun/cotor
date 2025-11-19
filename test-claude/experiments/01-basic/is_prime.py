def is_prime(n):
    """
    주어진 정수가 소수인지 판별합니다.

    Args:
        n (int): 판별할 정수

    Returns:
        bool: n이 소수이면 True, 아니면 False
    """
    if n <= 1:
        return False
    if n <= 3:
        return True
    if n % 2 == 0 or n % 3 == 0:
        return False

    # 6k ± 1 최적화: 모든 소수는 6k ± 1 형태
    i = 5
    while i * i <= n:
        if n % i == 0 or n % (i + 2) == 0:
            return False
        i += 6

    return True
