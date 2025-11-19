def bubble_sort(arr):
    """
    버블 정렬 알고리즘 구현

    인접한 두 원소를 비교하여 정렬하는 알고리즘입니다.
    가장 큰 값이 배열의 끝으로 "버블"처럼 이동합니다.

    Args:
        arr: 정수 리스트

    Returns:
        정렬된 정수 리스트

    Time Complexity: O(n^2)
    Space Complexity: O(1)
    """
    # 원본 리스트를 수정하지 않기 위해 복사본 생성
    result = arr.copy()
    n = len(result)

    # 외부 루프: n-1번 반복
    for i in range(n - 1):
        # 최적화: 스왑이 발생하지 않으면 이미 정렬된 상태
        swapped = False

        # 내부 루프: 인접한 원소 비교 및 교환
        # 매 반복마다 가장 큰 원소가 끝으로 이동하므로
        # 비교 범위를 n-1-i까지로 제한
        for j in range(n - 1 - i):
            # 현재 원소가 다음 원소보다 크면 교환
            if result[j] > result[j + 1]:
                result[j], result[j + 1] = result[j + 1], result[j]
                swapped = True

        # 스왑이 발생하지 않았다면 정렬 완료
        if not swapped:
            break

    return result


# 테스트 코드
if __name__ == "__main__":
    # 테스트 케이스 1: 일반적인 경우
    test1 = [64, 34, 25, 12, 22, 11, 90]
    print(f"원본: {test1}")
    print(f"정렬: {bubble_sort(test1)}")
    print()

    # 테스트 케이스 2: 이미 정렬된 경우
    test2 = [1, 2, 3, 4, 5]
    print(f"원본: {test2}")
    print(f"정렬: {bubble_sort(test2)}")
    print()

    # 테스트 케이스 3: 역순 정렬된 경우
    test3 = [5, 4, 3, 2, 1]
    print(f"원본: {test3}")
    print(f"정렬: {bubble_sort(test3)}")
    print()

    # 테스트 케이스 4: 중복 값이 있는 경우
    test4 = [3, 1, 4, 1, 5, 9, 2, 6, 5]
    print(f"원본: {test4}")
    print(f"정렬: {bubble_sort(test4)}")
    print()

    # 테스트 케이스 5: 빈 리스트
    test5 = []
    print(f"원본: {test5}")
    print(f"정렬: {bubble_sort(test5)}")
    print()

    # 테스트 케이스 6: 단일 원소
    test6 = [42]
    print(f"원본: {test6}")
    print(f"정렬: {bubble_sort(test6)}")
