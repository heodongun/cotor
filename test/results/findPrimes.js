/**
 * N까지의 모든 소수를 찾는 함수 (에라토스테네스의 체 알고리즘 사용)
 * @param {number} n - 소수를 찾을 최대 숫자
 * @returns {number[]} N 이하의 모든 소수 배열
 */
function findPrimes(n) {
  if (n < 2) return [];

  // 에라토스테네스의 체 초기화
  const isPrime = new Array(n + 1).fill(true);
  isPrime[0] = false;
  isPrime[1] = false;

  // 2부터 sqrt(n)까지 순회하며 배수 제거
  for (let i = 2; i * i <= n; i++) {
    if (isPrime[i]) {
      // i의 배수들을 모두 소수가 아닌 것으로 표시
      for (let j = i * i; j <= n; j += i) {
        isPrime[j] = false;
      }
    }
  }

  // 소수만 추출하여 배열로 반환
  const primes = [];
  for (let i = 2; i <= n; i++) {
    if (isPrime[i]) {
      primes.push(i);
    }
  }

  return primes;
}

/**
 * 단일 숫자가 소수인지 확인하는 함수
 * @param {number} num - 확인할 숫자
 * @returns {boolean} 소수 여부
 */
function isPrime(num) {
  if (num < 2) return false;
  if (num === 2) return true;
  if (num % 2 === 0) return false;

  for (let i = 3; i * i <= num; i += 2) {
    if (num % i === 0) return false;
  }

  return true;
}

// 테스트 및 사용 예제
if (require.main === module) {
  console.log('=== 소수 찾기 테스트 ===\n');

  // 테스트 케이스 1: 100까지의 소수
  console.log('100까지의 소수:');
  const primes100 = findPrimes(100);
  console.log(primes100);
  console.log(`총 ${primes100.length}개\n`);

  // 테스트 케이스 2: 50까지의 소수
  console.log('50까지의 소수:');
  const primes50 = findPrimes(50);
  console.log(primes50);
  console.log(`총 ${primes50.length}개\n`);

  // 테스트 케이스 3: 개별 숫자 소수 판별
  console.log('개별 숫자 소수 판별:');
  const testNumbers = [2, 17, 23, 25, 29, 100, 97];
  testNumbers.forEach(num => {
    console.log(`${num}은(는) ${isPrime(num) ? '소수입니다' : '소수가 아닙니다'}`);
  });

  // 성능 테스트
  console.log('\n=== 성능 테스트 ===');
  const testSize = 1000000;
  console.time(`${testSize}까지의 소수 찾기`);
  const largeResult = findPrimes(testSize);
  console.timeEnd(`${testSize}까지의 소수 찾기`);
  console.log(`결과: ${largeResult.length}개의 소수 발견`);
}

// 모듈로 내보내기
module.exports = {
  findPrimes,
  isPrime
};
