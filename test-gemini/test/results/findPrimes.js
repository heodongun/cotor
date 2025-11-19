/**
 * Find all prime numbers up to N using the Sieve of Eratosthenes algorithm
 * @param {number} n - The upper limit to find primes
 * @returns {number[]} Array of prime numbers up to N
 */
function findPrimes(n) {
  if (n < 2) {
    return [];
  }

  // Create a boolean array and initialize all entries as true
  const isPrime = new Array(n + 1).fill(true);
  isPrime[0] = false;
  isPrime[1] = false;

  // Sieve of Eratosthenes algorithm
  for (let i = 2; i * i <= n; i++) {
    if (isPrime[i]) {
      // Mark all multiples of i as not prime
      for (let j = i * i; j <= n; j += i) {
        isPrime[j] = false;
      }
    }
  }

  // Collect all prime numbers
  const primes = [];
  for (let i = 2; i <= n; i++) {
    if (isPrime[i]) {
      primes.push(i);
    }
  }

  return primes;
}

// Export for use in other modules
module.exports = findPrimes;

// Example usage and tests
if (require.main === module) {
  console.log('Prime numbers up to 10:', findPrimes(10));
  // Expected: [2, 3, 5, 7]

  console.log('Prime numbers up to 30:', findPrimes(30));
  // Expected: [2, 3, 5, 7, 11, 13, 17, 19, 23, 29]

  console.log('Prime numbers up to 100:', findPrimes(100));
  // Expected: [2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79, 83, 89, 97]

  console.log('Prime numbers up to 1:', findPrimes(1));
  // Expected: []

  console.log('Number of primes up to 1000:', findPrimes(1000).length);
  // Expected: 168
}
