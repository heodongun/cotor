def find_primes(n):
    """
    Finds all prime numbers up to n using the Sieve of Eratosthenes.
    """
    if n < 2:
        return []
    
    # Create a boolean list "is_prime" and initialize all entries
    # it as True. A value in is_prime[i] will finally be False
    # if i is Not a prime, else True.
    is_prime = [True] * (n + 1)
    is_prime[0] = is_prime[1] = False
    
    for p in range(2, int(n**0.5) + 1):
        if is_prime[p]:
            # Updating all multiples of p
            for i in range(p*p, n + 1, p):
                is_prime[i] = False
                
    primes = []
    for p in range(2, n + 1):
        if is_prime[p]:
            primes.append(p)
            
    return primes

if __name__ == '__main__':
    num = 30
    primes_up_to_num = find_primes(num)
    print(f"Prime numbers up to {num} are: {primes_up_to_num}")

    num = 100
    primes_up_to_num = find_primes(num)
    print(f"Prime numbers up to {num} are: {primes_up_to_num}")