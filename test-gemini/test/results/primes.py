def find_primes_up_to_n(n):
    """
    N까지의 모든 소수를 찾는 함수 (에라토스테네스의 체 알고리즘 사용)

    :param n: 소수를 찾을 범위의 상한값 (n > 1)
    :return: 2부터 n까지의 소수 리스트
    """
    if n < 2:
        return []

    # n+1 크기의 boolean 리스트를 생성하고 모두 True로 초기화
    # is_prime[i]는 숫자 i가 소수인지 여부를 저장
    is_prime = [True] * (n + 1)
    is_prime[0] = is_prime[1] = False  # 0과 1은 소수가 아님

    # 2부터 sqrt(n)까지 반복
    # p*p > n 이면 더 이상 확인할 필요 없음
    for p in range(2, int(n**0.5) + 1):
        if is_prime[p]:
            # p가 소수인 경우, p의 배수들을 모두 소수가 아니라고 표시
            for multiple in range(p * p, n + 1, p):
                is_prime[multiple] = False

    # is_prime 리스트에서 True로 표시된 인덱스(숫자)들을 모아 리스트로 반환
    primes = [p for p in range(n + 1) if is_prime[p]]
    return primes

if __name__ == '__main__':
    # 테스트 예제
    upper_limit = 100
    prime_numbers = find_primes_up_to_n(upper_limit)
    print(f"2부터 {upper_limit}까지의 소수: {prime_numbers}")

    upper_limit = 20
    prime_numbers = find_primes_up_to_n(upper_limit)
    print(f"2부터 {upper_limit}까지의 소수: {prime_numbers}")
